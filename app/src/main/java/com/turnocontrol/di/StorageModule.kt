package com.turnocontrol.di

import com.turnocontrol.data.*
import com.turnocontrol.data.accounts.StorageSessionHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Storage comes from the active session, not from process-wide singletons. Unscoped on purpose: each injection asks
 * the holder for the current session's instance, so screens created after an account switch never see the previous
 * account's database. Within one session the holder returns the same instances.
 */
@Module @InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides fun database(holder: StorageSessionHolder): TurnoDatabase = holder.scope().database
    @Provides fun schedule(holder: StorageSessionHolder): ScheduleRepository = holder.scope().schedule
    @Provides fun configuration(holder: StorageSessionHolder): ConfigurationRepository = holder.scope().configuration
    @Provides fun backup(holder: StorageSessionHolder): BackupRepository = holder.scope().backup
    @Provides fun sync(holder: StorageSessionHolder): SyncStore = holder.scope().sync
    @Provides fun compatibility(holder: StorageSessionHolder): CompatibilityChecker = holder.scope().compatibility
}
