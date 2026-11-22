package com.turnocontrol.data.auth

import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.auth.*
import com.turnocontrol.domain.auth.FakeAuthProvider.Companion.ACCOUNT_A
import com.turnocontrol.domain.auth.FakeAuthProvider.Companion.ACCOUNT_B
import java.io.File
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests of the encrypted session vault. [JvmGcmCipher] uses real AES-256-GCM from the JVM, standing in for the
 * Android Keystore, which Robolectric cannot provide: Keystore behaviour itself still needs an Android device/emulator.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SessionVaultTest {
    @get:Rule val temporary = TemporaryFolder()

    class JvmGcmCipher : SessionCipher {
        val keys = mutableMapOf<String, SecretKey>()
        val invalidated = mutableSetOf<String>()
        val ivs = mutableListOf<String>()
        var failEncrypt = false
        override fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
            if (failEncrypt) throw IllegalStateException("fallo inyectado")
            val key = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(aad) }
            ivs += cipher.iv.joinToString("") { "%02x".format(it) }
            return cipher.iv to cipher.doFinal(plaintext)
        }
        override fun decrypt(alias: String, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            if (alias in invalidated) throw SessionKeyUnavailableException()
            val key = keys[alias] ?: throw SessionKeyUnavailableException()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }.doFinal(ciphertext)
        }
        override fun deleteKey(alias: String) { keys.remove(alias); invalidated.remove(alias) }
    }

    private val cipher = JvmGcmCipher()
    private val dir get() = File(temporary.root, "nobackup/sessions")
    private val vault get() = EncryptedSessionVault(dir, cipher)
    private fun session(account: AccountId, handle: String = "token-sintetico-${account.value.takeLast(1)}") =
        AuthSession(Identity(account, AuthMethod.EMAIL, "a***@example.invalid"), handle, Instant.parse("2026-12-01T11:00:00Z"))
    private fun onlyFile() = dir.listFiles()!!.single { it.name.endsWith(".session") }

    @Test fun savesAndRestoresTheSameAccountOnly() {
        vault.save(session(ACCOUNT_A))
        assertEquals(SessionLoad.Found(session(ACCOUNT_A)), vault.load(ACCOUNT_A))
        assertEquals(SessionLoad.Missing, vault.load(ACCOUNT_B))
        val fileA = onlyFile()
        assertFalse(fileA.name.contains(ACCOUNT_A.value)) // The raw id never names a file.
        // A's file placed where B's would be never restores as B (account bound as AAD, separate key).
        fileA.copyTo(File(dir, com.turnocontrol.data.accounts.StorageNamespace.forAccount(ACCOUNT_B).key + ".session"))
        assertTrue(vault.load(ACCOUNT_B) is SessionLoad.RequiresSignIn)
    }

    @Test fun storedBytesContainNoPlaintextAndIvsAreNeverReused() {
        repeat(50) { vault.save(session(ACCOUNT_A, "token-secreto-$it")) }
        val bytes = String(onlyFile().readBytes(), Charsets.ISO_8859_1)
        listOf("token-secreto", "example.invalid", ACCOUNT_A.value, "EMAIL").forEach { assertFalse(it, bytes.contains(it)) }
        assertEquals(cipher.ivs.size, cipher.ivs.distinct().size)
        assertEquals("token-secreto-49", (vault.load(ACCOUNT_A) as SessionLoad.Found).session.handle) // Replacement keeps the latest.
    }

    @Test fun corruptedTruncatedOrUnknownFilesRequireSignIn() {
        vault.save(session(ACCOUNT_A))
        val good = onlyFile().readBytes()
        fun check(bytes: ByteArray, reason: UnreadableReason) { onlyFile().writeBytes(bytes); assertEquals(SessionLoad.RequiresSignIn(reason), vault.load(ACCOUNT_A)) }
        check(good.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }, UnreadableReason.CORRUPTED)
        check(good.copyOf(good.size / 2), UnreadableReason.CORRUPTED)
        check(good.copyOf().also { it[0] = 'X'.code.toByte() }, UnreadableReason.UNSUPPORTED_VERSION)
        check(good.copyOf().also { it[4] = 2 }, UnreadableReason.UNSUPPORTED_VERSION)
        check(ByteArray(0), UnreadableReason.CORRUPTED)
    }

    @Test fun missingOrInvalidatedKeysRequireSignIn() {
        vault.save(session(ACCOUNT_A))
        val alias = cipher.keys.keys.single()
        cipher.invalidated += alias
        assertEquals(SessionLoad.RequiresSignIn(UnreadableReason.KEY_UNAVAILABLE), vault.load(ACCOUNT_A))
        cipher.invalidated.clear(); cipher.keys.clear() // e.g. restored onto another device: the file without its key.
        assertEquals(SessionLoad.RequiresSignIn(UnreadableReason.KEY_UNAVAILABLE), vault.load(ACCOUNT_A))
    }

    @Test fun interruptedWritesKeepTheLastCompleteSession() {
        vault.save(session(ACCOUNT_A, "token-antiguo"))
        File(dir, onlyFile().name + ".tmp").writeBytes(byteArrayOf(1, 2, 3)) // A temporary file left by a crash.
        assertEquals("token-antiguo", (vault.load(ACCOUNT_A) as SessionLoad.Found).session.handle)
        cipher.failEncrypt = true
        assertThrows(IllegalStateException::class.java) { vault.save(session(ACCOUNT_A, "token-nuevo")) }
        cipher.failEncrypt = false
        assertEquals("token-antiguo", (vault.load(ACCOUNT_A) as SessionLoad.Found).session.handle)
    }

    @Test fun clearingIsIdempotentAndRemovesTheKey() {
        vault.save(session(ACCOUNT_A))
        vault.clear(ACCOUNT_A); vault.clear(ACCOUNT_A)
        assertEquals(SessionLoad.Missing, vault.load(ACCOUNT_A))
        assertTrue(cipher.keys.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    // Integration with the authentication state machine.

    private var now = Instant.parse("2026-12-01T10:00:00Z")
    private val provider = FakeAuthProvider { now }
    private fun manager() = AuthManager(provider, vault) { now }
    private fun signIn(account: String, password: String) = SignInRequest.Email(account, password.toCharArray())

    @Test fun signInSwitchRevocationAndSignOutKeepTheVaultConsistent() = runTest {
        val auth = manager()
        auth.signIn(signIn("ana@example.invalid", "clave-sintetica-a"))
        assertTrue(vault.load(ACCOUNT_A) is SessionLoad.Found)
        auth.switchAccount(signIn("bea@example.invalid", "mal")) // Failed switch: A's stored session is untouched.
        assertTrue(vault.load(ACCOUNT_A) is SessionLoad.Found)
        auth.switchAccount(signIn("bea@example.invalid", "clave-sintetica-b"))
        assertEquals(SessionLoad.Missing, vault.load(ACCOUNT_A))
        assertTrue(vault.load(ACCOUNT_B) is SessionLoad.Found)
        provider.revoke((auth.state as AuthState.Authenticated).session)
        auth.refreshValidity()
        assertEquals(SessionLoad.Missing, vault.load(ACCOUNT_B)) // A revoked session is not kept.
        auth.signOut(); auth.signOut()
        assertEquals(AuthState.Guest, auth.state)
    }

    @Test fun restartRestoresOnlyUsableSessionsAndNeverAuthorizesBrokenOnes() = runTest {
        manager().signIn(signIn("ana@example.invalid", "clave-sintetica-a"))
        // Restart while valid.
        val restored = restoreStoredSession(manager().also { }, vault, ACCOUNT_A)
        assertTrue((restored as SessionRestore.Restored).state is AuthState.Authenticated)
        // Restart after expiry: identity known, no authorization until the same account renews.
        now = now.plusSeconds(FakeAuthProvider.SESSION_SECONDS)
        val expiredManager = manager()
        assertEquals(ExpiryReason.EXPIRED, ((restoreStoredSession(expiredManager, vault, ACCOUNT_A) as SessionRestore.Restored).state as AuthState.SessionExpired).reason)
        assertNull(expiredManager.authorize())
        // Restart with an unreadable session: deleted, sign in again, still a guest.
        onlyFile().writeBytes(byteArrayOf(0))
        val broken = manager()
        assertEquals(SessionRestore.SignInAgain(ACCOUNT_A, UnreadableReason.CORRUPTED), restoreStoredSession(broken, vault, ACCOUNT_A))
        assertEquals(AuthState.Guest, broken.state)
        assertNull(broken.authorize())
        assertEquals(SessionLoad.Missing, vault.load(ACCOUNT_A))
    }

    // Backups

    @Test fun sessionsLiveOutsideEveryBackup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val production = SessionVaults.forContext(context)
        val field = EncryptedSessionVault::class.java.getDeclaredField("directory").apply { isAccessible = true }
        assertTrue((field.get(production) as File).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val rules = File("src/main/res/xml/backup_rules.xml").readText()
        listOf("root", "file", "database", "sharedpref", "external").forEach { assertTrue(it, rules.contains("domain=\"$it\" path=\".\"")) }
        val extraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(extraction.contains("<cloud-backup>") && extraction.contains("<device-transfer>"))
        assertEquals(2, Regex("domain=\"file\" path=\"\\.\"").findAll(extraction).count())
    }
}
