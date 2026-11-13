package com.dd3boh.xenowave.di

import com.dd3boh.xenowave.utils.search.OnlineSearchSource
import com.dd3boh.xenowave.utils.search.YouTubeOnlineSearchSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {
    @Provides
    @Singleton
    fun provideOnlineSearchSource(): OnlineSearchSource = YouTubeOnlineSearchSource
}
