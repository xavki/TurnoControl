package com.turnocontrol.di

import com.turnocontrol.data.accounts.StorageSessionHolder
import com.turnocontrol.data.auth.AuthCoordinator
import com.turnocontrol.data.auth.EncryptedSessionVault
import com.turnocontrol.data.auth.SessionVaults
import com.turnocontrol.domain.auth.AuthError
import com.turnocontrol.domain.auth.AuthManager
import com.turnocontrol.domain.auth.AuthProvider
import com.turnocontrol.domain.auth.AuthResult
import com.turnocontrol.domain.auth.AuthSession
import com.turnocontrol.domain.auth.SessionValidity
import com.turnocontrol.domain.auth.SignInRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton

/*
 * Phase 5E.1. No Google or email backend exists yet (that is phase 5F.1, pending Supabase/Credential Manager setup
 * with the owner's explicit approval), so production gets an [AuthProvider] that can never succeed: every attempt is
 * a deterministic, offline failure. This keeps AuthManager/AuthCoordinator fully wired end-to-end in production -
 * ready for a real provider to be swapped in later without touching this module's shape - while guaranteeing nobody
 * can sign in for real today. The account UI's own `providersAvailable = false` is a second, independent gate: even
 * if this provider changes later, the UI stays disabled until that is turned on deliberately.
 */
private object UnconfiguredAuthProvider : AuthProvider {
    override suspend fun signIn(request: SignInRequest): AuthResult = AuthResult.Failure(AuthError.Unknown("provider_not_configured"))
    override suspend fun signOut(session: AuthSession) = Unit
    override fun validity(session: AuthSession, now: Instant): SessionValidity = SessionValidity.REVOKED
}

@Module @InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides @Singleton fun sessionVault(@ApplicationContext context: android.content.Context): EncryptedSessionVault = SessionVaults.forContext(context)

    @Provides @Singleton fun authManager(vault: EncryptedSessionVault): AuthManager = AuthManager(UnconfiguredAuthProvider, vault)

    @Provides @Singleton fun authCoordinator(manager: AuthManager, vault: EncryptedSessionVault, storage: StorageSessionHolder): AuthCoordinator =
        AuthCoordinator(manager, vault, storage)
}
