package com.turnocontrol.data.accounts

import androidx.room.withTransaction
import com.turnocontrol.data.*
import com.turnocontrol.domain.DataFormat
import com.turnocontrol.domain.auth.AccountId
import com.turnocontrol.domain.sync.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/*
 * Safe first-time linking (phase 5B): copies explicitly approved guest data into an account's own database. Not wired
 * into the UI; real authentication does not exist yet. Guarantees:
 *  - The guest database is only read; it is never modified, linked or deleted.
 *  - Ownership is verified (namespace derived from the account id, database bound to that account, no open conflicts).
 *  - The preview id must still match the data: decisions are never applied to data that changed meanwhile.
 *  - Recovery backups of both datasets are written before anything changes; a failure there aborts.
 *  - All account changes (records and their outgoing operations) commit in ONE Room transaction, under the operation
 *    gate, so no account switch, save, restore or synchronization can interleave.
 *  - A namespace-tagged journal makes a crash recoverable: the transaction either fully happened (verified -> complete)
 *    or not at all (journal discarded). A journal of another account is never acted on.
 */

sealed interface LinkOutcome {
    data class Applied(val written: Int, val replacedInAccount: Int, val skippedFromGuest: Int) : LinkOutcome
    data object AlreadyLinked : LinkOutcome
    data object Cancelled : LinkOutcome
    data class NeedsDecisions(val keys: List<RecordKey>) : LinkOutcome
    data class NeedsAcknowledgement(val keys: List<RecordKey>) : LinkOutcome
    data class Invalid(val reason: String) : LinkOutcome
    /** The data changed since the preview; show a new preview. */
    data object PlanChanged : LinkOutcome
    data class Refused(val reason: String) : LinkOutcome
    data class BackupFailed(val reason: String) : LinkOutcome
}

enum class LinkRecovery { NONE, COMPLETED, DISCARDED }

class AccountLinker(private val gate: OperationGate, private val clock: () -> Instant = Instant::now) {
    /** Failure-injection hook for tests: runs inside the account transaction, after the records are written. */
    internal var beforeCommit: suspend () -> Unit = {}

    private val order = listOf(SyncEntity.PROFILE, SyncEntity.CONTRACT, SyncEntity.PAY_RULE, SyncEntity.SCHEDULED_DAY, SyncEntity.PAYSLIP)
    private fun directory(account: StorageSession) = File(account.location.recoveryDirectory.parentFile, "linking")
    private fun journal(account: StorageSession) = File(directory(account), "link.journal")
    private fun sha(text: String?) = MessageDigest.getInstance("SHA-256").digest((text ?: "-").toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The account's data as the server and its queue see it. A record that is neither known to the server nor pending
     * (the "Mi calendario" placeholder profile created locally when remote days were applied) is not account data:
     * in a linked database every real edit is captured, so it is left out and cannot cause a false conflict.
     */
    private suspend fun accountState(db: TurnoDatabase) = db.withTransaction {
        val dao = db.sync()
        val known = dao.recordStates().associate { r -> RecordKey(SyncEntity.valueOf(r.entity), r.recordKey).let { it to r.known(it) } }
        val pending = dao.outbox().map { RecordKey(SyncEntity.valueOf(it.entity), it.recordKey) }.toSet()
        Triple(SyncRecords.all(db).filterKeys { it in known || it in pending }, known,
            dao.quarantine().map { RecordKey(SyncEntity.valueOf(it.entity), it.recordKey) }.toSet())
    }
    private suspend fun guestRecords(guest: TurnoDatabase) = guest.withTransaction { SyncRecords.all(guest) }

    /** Read-only preview. [guest] is the guest database opened for reading; [account] the open account session. */
    suspend fun preview(guest: TurnoDatabase, account: StorageSession): LinkPlan {
        require(account.location.namespace is StorageNamespace.Account) { "La vinculación solo copia datos a una cuenta." }
        val (records, known, quarantined) = accountState(account.database)
        return LinkMergePlanner.plan(guestRecords(guest), records, known, quarantined)
    }

    suspend fun apply(guest: TurnoDatabase, account: StorageSession, accountId: AccountId, planId: String,
                      decisions: Map<RecordKey, Resolution>, acknowledgeReplacements: Boolean): LinkOutcome = gate.operation("account-link") {
        val db = account.database
        if (account.location.namespace != StorageNamespace.forAccount(accountId)) return@operation LinkOutcome.Refused("La cuenta no corresponde a estos datos.")
        val state = db.sync().state()
        if (state?.accountId != accountId.value) return@operation LinkOutcome.Refused("Los datos de destino no pertenecen a esta cuenta.")
        if (db.sync().conflicts().isNotEmpty()) return@operation LinkOutcome.Refused("Resuelve primero los conflictos de sincronización de la cuenta.")
        val journalText = journal(account).takeIf { it.exists() }?.readText()
        if (journalText != null && journalText.startsWith("COMPLETE $planId ")) return@operation LinkOutcome.AlreadyLinked
        if (journalText != null && journalText.startsWith("APPLYING")) return@operation LinkOutcome.Refused("Hay una vinculación interrumpida pendiente de recuperar.")

        val (records, known, quarantined) = accountState(db)
        val plan = LinkMergePlanner.plan(guestRecords(guest), records, known, quarantined)
        if (plan.id != planId) return@operation LinkOutcome.PlanChanged
        val ready = when (val resolution = LinkMergePlanner.resolve(plan, records, decisions, acknowledgeReplacements)) {
            is LinkResolution.Ready -> resolution
            is LinkResolution.NeedsDecisions -> return@operation LinkOutcome.NeedsDecisions(resolution.keys)
            is LinkResolution.NeedsAcknowledgement -> return@operation LinkOutcome.NeedsAcknowledgement(resolution.replacements)
            LinkResolution.Cancelled -> return@operation LinkOutcome.Cancelled
            is LinkResolution.Invalid -> return@operation LinkOutcome.Invalid(resolution.reason)
        }

        // Recovery copies of both datasets before any change. Any failure here aborts with nothing modified.
        try {
            val header = BackupHeader(BackupCodec.FORMAT_VERSION, "vinculación", 0, DataFormat.ROOM_SCHEMA_VERSION, DataFormat.CURRENT, clock())
            write(File(directory(account), "pre-link-guest.json"), BackupCodec.encode(BackupFile(header, snapshot(guest))))
            write(File(directory(account), "pre-link-account.json"), BackupCodec.encode(BackupFile(header, snapshot(db))))
        } catch (e: Exception) {
            return@operation LinkOutcome.BackupFailed("No se pudo guardar la copia previa a la vinculación. No se ha cambiado nada.")
        }

        val expected = ready.changes.entries.joinToString("\n") { (k, v) -> "$k\t${sha((v as LocalRecord.Shared?)?.payload?.text)}" }
        write(journal(account), "APPLYING $planId ${account.location.namespace.tag}\n$expected")
        db.withTransaction {
            ready.changes.entries.sortedBy { order.indexOf(it.key.entity) }.forEach { (key, value) ->
                check(SyncRecords.apply(db, key, value)) { "Este dispositivo no puede guardar $key." }
            }
            beforeCommit()
            SyncCapture.record(db, ready.changes) // Outgoing operations, based on the account's known versions.
        }
        check(verify(db, ready.changes)) { "La vinculación no se pudo verificar." }
        write(journal(account), "COMPLETE $planId ${account.location.namespace.tag}")
        LinkOutcome.Applied(ready.changes.size, ready.replacedInAccount.size, ready.skippedFromGuest.size)
    }

    /** At startup or before a new attempt: settles an interrupted linking of THIS account only. */
    suspend fun recover(account: StorageSession): LinkRecovery {
        val file = journal(account)
        if (!file.exists()) return LinkRecovery.NONE
        val lines = file.readText().lines()
        val header = lines.first().split(" ")
        if (header.getOrNull(2) != account.location.namespace.tag) return LinkRecovery.NONE // Another account's journal: untouched.
        if (header[0] != "APPLYING") return LinkRecovery.NONE
        val current = SyncRecords.all(account.database)
        val applied = lines.drop(1).filter { it.isNotBlank() }.all { line ->
            val (key, digest) = line.split("\t")
            val (entity, id) = key.split(":", limit = 2)
            sha((current[RecordKey(SyncEntity.valueOf(entity), id)] as LocalRecord.Shared?)?.payload?.text) == digest
        }
        return if (applied) { write(file, "COMPLETE ${header[1]} ${header[2]}"); LinkRecovery.COMPLETED }
            else { file.delete(); LinkRecovery.DISCARDED } // The transaction never committed: the account is unchanged.
    }

    private suspend fun verify(db: TurnoDatabase, changes: Map<RecordKey, LocalRecord?>): Boolean {
        val current = SyncRecords.all(db)
        return changes.all { (k, v) -> current[k] == v } && SyncRules.validate(current) == null
    }

    private suspend fun snapshot(db: TurnoDatabase): BackupSnapshot = db.withTransaction {
        val dao = db.backup()
        BackupSnapshot(dao.profiles(), dao.contracts(), dao.payRules(), dao.days(), dao.intervals(), false)
    }

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
