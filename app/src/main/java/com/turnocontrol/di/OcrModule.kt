package com.turnocontrol.di

import com.turnocontrol.data.ImageOcr
import com.turnocontrol.data.LocalImageOcr
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds abstract fun imageOcr(implementation: LocalImageOcr): ImageOcr
}
