package com.turnocontrol.domain.sync

import java.security.MessageDigest

/*
 * First-time account linking (phase 5B). Pure Kotlin. Compares the guest data with the account's current data (the
 * account database after a normal synchronization) and decides nothing on its own: identical and one-sided records
 * combine automatically, anything that differs needs an explicit decision, and every result is validated with the
 * same SyncRules as the rest of synchronization. The guest data itself is never modified by linking.
 */

enum class LinkKind {
    /** Only in the guest data: copied into the account (unless the user skips it). */
    IMPORT,
    /** Same content on both sides: nothing to do. */
    ALREADY_IDENTICAL,
    /** Only in the account: stays as it is. */
    KEEP_ACCOUNT,
    /** Different content: requires KEEP_LOCAL (guest) or KEEP_REMOTE (account). */
    CONFLICT,
    /** In the guest data but deleted in the account: requires a decision. */
    DELETED_IN_ACCOUNT,
    /** The account holds a record this version cannot read (quarantined): left untouched, never merged. */
    UNSUPPORTED,
}

data class LinkEntry(val key: RecordKey, val kind: LinkKind, val guest: LocalRecord?, val account: LocalRecord?)

/** A preview. [id] fingerprints both datasets, so a decision can never be applied to data that changed since. */
data class LinkPlan(val entries: List<LinkEntry>, val id: String) {
    fun count(kind: LinkKind) = entries.count { it.kind == kind }
    val decisionsNeeded: List<RecordKey> get() = entries.filter { it.kind == LinkKind.CONFLICT || it.kind == LinkKind.DELETED_IN_ACCOUNT }.map { it.key }
}

sealed interface LinkResolution {
    /** Changes to write into the account database (null = delete), plus what each decision discards. */
    data class Ready(val changes: Map<RecordKey, LocalRecord?>, val replacedInAccount: List<RecordKey>, val skippedFromGuest: List<RecordKey>) : LinkResolution
    data class NeedsDecisions(val keys: List<RecordKey>) : LinkResolution
    /** Replacing account data with guest data is high risk: it needs a second, explicit confirmation. */
    data class NeedsAcknowledgement(val replacements: List<RecordKey>) : LinkResolution
    data object Cancelled : LinkResolution
    data class Invalid(val reason: String) : LinkResolution
}

object LinkMergePlanner {
    fun plan(guest: Map<RecordKey, LocalRecord>, account: Map<RecordKey, LocalRecord>, accountKnown: Map<RecordKey, KnownVersion>,
             accountQuarantined: Set<RecordKey> = emptySet()): LinkPlan {
        val keys = (guest.keys + account.keys + accountQuarantined).sortedWith(compareBy({ it.entity.ordinal }, { it.key }))
        val entries = keys.mapNotNull { key ->
            val g = guest[key]; val a = account[key]
            val kind = when {
                key in accountQuarantined -> LinkKind.UNSUPPORTED
                g == null && a == null -> return@mapNotNull null
                g == null -> LinkKind.KEEP_ACCOUNT
                a == null && accountKnown[key]?.deleted == true -> LinkKind.DELETED_IN_ACCOUNT
                a == null -> LinkKind.IMPORT
                g == a -> LinkKind.ALREADY_IDENTICAL
                else -> LinkKind.CONFLICT
            }
            LinkEntry(key, kind, g, a)
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(entries.joinToString("\n") { e ->
            "${e.key}|${e.kind}|${(e.guest as LocalRecord.Shared?)?.payload?.text}|${(e.account as LocalRecord.Shared?)?.payload?.text}|${accountKnown[e.key]?.version}"
        }.toByteArray()).joinToString("") { "%02x".format(it) }
        return LinkPlan(entries, fingerprint)
    }

    /**
     * [decisions]: KEEP_LOCAL (guest) / KEEP_REMOTE (account) per conflict or deletion; KEEP_REMOTE on an IMPORT entry
     * skips it; CANCEL anywhere cancels the whole linking. [accountRecords] is the account's current data, used to
     * validate the combined result across records (overnight shifts, rate periods).
     */
    fun resolve(plan: LinkPlan, accountRecords: Map<RecordKey, LocalRecord>, decisions: Map<RecordKey, Resolution>, acknowledgeReplacements: Boolean): LinkResolution {
        if (decisions.values.any { it == Resolution.CANCEL }) return LinkResolution.Cancelled
        val missing = plan.decisionsNeeded.filter { it !in decisions }
        if (missing.isNotEmpty()) return LinkResolution.NeedsDecisions(missing)
        val changes = linkedMapOf<RecordKey, LocalRecord?>()
        val replaced = mutableListOf<RecordKey>(); val skipped = mutableListOf<RecordKey>()
        plan.entries.forEach { e ->
            when (e.kind) {
                LinkKind.IMPORT -> if (decisions[e.key] == Resolution.KEEP_REMOTE) skipped += e.key else changes[e.key] = e.guest
                LinkKind.CONFLICT, LinkKind.DELETED_IN_ACCOUNT -> when (decisions.getValue(e.key)) {
                    Resolution.KEEP_LOCAL -> { changes[e.key] = e.guest; replaced += e.key }
                    else -> skipped += e.key
                }
                LinkKind.ALREADY_IDENTICAL, LinkKind.KEEP_ACCOUNT, LinkKind.UNSUPPORTED -> Unit
            }
        }
        // Only account content that is replaced counts as high risk; a skipped guest record still exists in the guest data.
        val risky = replaced.filter { key -> plan.entries.first { it.key == key }.kind == LinkKind.CONFLICT }
        if (risky.isNotEmpty() && !acknowledgeReplacements) return LinkResolution.NeedsAcknowledgement(risky)
        val combined = accountRecords.toMutableMap().apply { changes.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) } }
        SyncRules.validate(combined)?.let { return LinkResolution.Invalid(it) }
        return LinkResolution.Ready(changes, replaced, skipped)
    }
}
