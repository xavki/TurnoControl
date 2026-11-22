package com.turnocontrol.di

import android.content.Context
import androidx.room.Room
import com.turnocontrol.data.TurnoDatabase
import com.turnocontrol.data.ALL_MIGRATIONS
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): TurnoDatabase =
        Room.databaseBuilder(context, TurnoDatabase::class.java, "turnocontrol.db").addMigrations(*ALL_MIGRATIONS).build()
}
