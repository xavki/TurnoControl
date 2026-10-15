package com.turnocontrol

import com.turnocontrol.data.TurnoDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Test access exists only in the isolated verification APK. */
@EntryPoint @InstallIn(SingletonComponent::class)
interface CalendarTestDatabase { fun database(): TurnoDatabase }
