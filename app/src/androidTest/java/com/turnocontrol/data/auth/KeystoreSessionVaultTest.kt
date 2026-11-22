package com.turnocontrol.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.domain.auth.AccountId
import com.turnocontrol.domain.auth.AuthMethod
import com.turnocontrol.domain.auth.AuthSession
import com.turnocontrol.domain.auth.Identity
import java.io.File
import java.security.KeyStore
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The REAL Android Keystore (the JVM tests use an AES-GCM stand-in). Runs only in the isolated `.verification` app on
 * an emulator or test device, after explicit owner approval to build and install it. Synthetic accounts and tokens only.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = AccountId.parse("00000000-0000-4000-8000-0000000007a1")!!
    private val b = AccountId.parse("00000000-0000-4000-8000-0000000007b2")!!
    private val dir = File(context.noBackupFilesDir, "sessions-instrumented-test")
    private val vault = EncryptedSessionVault(dir, KeystoreSessionCipher())
    private fun session(account: AccountId, token: String) = AuthSession(Identity(account, AuthMethod.EMAIL, "a***@example.invalid"), token, Instant.parse("2030-01-01T00:00:00Z"))
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun aliases() = keystore().aliases().toList().filter { it.startsWith("turnocontrol-session-") }

    @After fun cleanup() { vault.clear(a); vault.clear(b); dir.deleteRecursively() }

    @Test fun createsAPerAccountKeyAndRoundTripsASession() {
        vault.save(session(a, "token-sintetico-a"))
        assertEquals("token-sintetico-a", (vault.load(a) as SessionLoad.Found).session.handle)
        assertEquals(1, aliases().size)
        assertFalse(String(dir.listFiles()!!.single().readBytes(), Charsets.ISO_8859_1).contains("token-sintetico-a"))
    }

    @Test fun aRecreatedVaultRestoresTheSessionLikeAnAppRestart() {
        vault.save(session(a, "token-reinicio"))
        val afterRestart = EncryptedSessionVault(dir, KeystoreSessionCipher())
        assertEquals("token-reinicio", (afterRestart.load(a) as SessionLoad.Found).session.handle)
    }

    @Test fun accountBCannotReadAccountASession() {
        vault.save(session(a, "token-a"))
        val fileA = dir.listFiles()!!.single()
        fileA.copyTo(File(dir, com.turnocontrol.data.accounts.StorageNamespace.forAccount(b).key + ".session"))
        assertTrue(vault.load(b) is SessionLoad.RequiresSignIn)
    }

    @Test fun modifiedCiphertextIsDetected() {
        vault.save(session(a, "token-a"))
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
        file.writeBytes(bytes)
        assertEquals(SessionLoad.RequiresSignIn(UnreadableReason.CORRUPTED), vault.load(a))
    }

    @Test fun aMissingKeyRequiresSigningInAgain() {
        vault.save(session(a, "token-a"))
        aliases().forEach { keystore().deleteEntry(it) } // Same effect as a key invalidated by the system.
        assertEquals(SessionLoad.RequiresSignIn(UnreadableReason.KEY_UNAVAILABLE), vault.load(a))
    }

    @Test fun signingOutRemovesTheSessionAndItsKey() {
        vault.save(session(a, "token-a"))
        vault.clear(a)
        assertEquals(SessionLoad.Missing, vault.load(a))
        assertTrue(aliases().isEmpty())
    }

    @Test fun productionSessionsLiveOutsideBackups() {
        val production = SessionVaults.forContext(context)
        val field = EncryptedSessionVault::class.java.getDeclaredField("directory").apply { isAccessible = true }
        assertTrue((field.get(production) as File).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
