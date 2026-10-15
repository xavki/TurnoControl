package com.turnocontrol.di

import com.turnocontrol.domain.ScheduleInterpreter
import com.turnocontrol.domain.WorkerRowsInterpreter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ScheduleModule {
    @Provides fun interpreter(): ScheduleInterpreter = WorkerRowsInterpreter()
}
