package com.turnocontrol.domain.auth

import java.security.MessageDigest
import java.time.Instant

/**
 * Deterministic in-memory identity service for tests. No network, no real accounts, synthetic ids only. Passwords are
 * kept as SHA-256 digests of synthetic values (never plaintext), compared and then discarded by the caller.
 */
class FakeAuthProvider(private val clock: () -> Instant) : AuthProvider {
    companion object {
        val ACCOUNT_A = AccountId.parse("00000000-0000-4000-8000-00000000000a")!!
        val ACCOUNT_B = AccountId.parse("00000000-0000-4000-8000-00000000000b")!!
        const val SESSION_SECONDS = 3600L
        fun digest(chars: CharArray) = MessageDigest.getInstance("SHA-256").digest(String(chars).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private data class Account(val id: AccountId, val email: String, val passwordDigest: String, val googleToken: String)
    private val accounts = listOf(
        Account(ACCOUNT_A, "ana@example.invalid", digest("clave-sintetica-a".toCharArray()), "google-token-a"),
        Account(ACCOUNT_B, "bea@example.invalid", digest("clave-sintetica-b".toCharArray()), "google-token-b"),
    )
    private val revoked = mutableSetOf<String>()
    private var issued = 0
    var offline = false
    val signedOut = mutableListOf<AccountId>()
    /** Every request seen, as its toString(): lets tests prove secrets never appear in text form. */
    val seen = mutableListOf<String>()

    override suspend fun signIn(request: SignInRequest): AuthResult {
        seen += request.toString()
        if (offline) return AuthResult.Failure(AuthError.Network)
        val account = when (request) {
            is SignInRequest.Email -> accounts.firstOrNull { it.email == request.email && it.passwordDigest == digest(request.password) }
            is SignInRequest.Google -> if (request.rawNonce.isBlank()) null else accounts.firstOrNull { it.googleToken == request.idToken }
        } ?: return AuthResult.Failure(if (request is SignInRequest.Google && request.idToken == "cancelled") AuthError.Cancelled else AuthError.InvalidCredentials)
        val method = if (request is SignInRequest.Google) AuthMethod.GOOGLE else AuthMethod.EMAIL
        val masked = account.email.first() + "***@example.invalid"
        return AuthResult.Success(AuthSession(Identity(account.id, method, masked), "fake-session-${++issued}", clock().plusSeconds(SESSION_SECONDS)))
    }

    override suspend fun signOut(session: AuthSession) { signedOut += session.identity.accountId; revoked += session.handle }
    override fun validity(session: AuthSession, now: Instant) = when {
        session.handle in revoked -> SessionValidity.REVOKED
        !now.isBefore(session.expiresAt) -> SessionValidity.EXPIRED
        else -> SessionValidity.VALID
    }
    /** Server-side revocation (e.g. "sign out everywhere" from another device). */
    fun revoke(session: AuthSession) { revoked += session.handle }
}
