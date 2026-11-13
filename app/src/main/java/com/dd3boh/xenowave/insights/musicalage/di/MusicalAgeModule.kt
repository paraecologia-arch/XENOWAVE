package com.dd3boh.xenowave.insights.musicalage.di

import android.content.Context
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeEngine
import com.dd3boh.xenowave.insights.musicalage.data.ManualEvidenceRepository
import com.dd3boh.xenowave.insights.musicalage.data.MusicalAgeDao
import com.dd3boh.xenowave.insights.musicalage.data.MusicalAgeRepository
import com.dd3boh.xenowave.insights.musicalage.data.RoomSongMetadataSource
import com.dd3boh.xenowave.insights.musicalage.data.SongMetadataSource
import com.dd3boh.xenowave.insights.musicalage.metadata.FileRecordingEvidenceCache
import com.dd3boh.xenowave.insights.musicalage.metadata.KtorMetadataTransport
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataTransport
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzEnrichmentService
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzUserAgent
import com.dd3boh.xenowave.insights.musicalage.metadata.RecordingEvidenceCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MusicalAgeModule {
    @Singleton
    @Provides
    fun provideMusicalAgeDao(database: MusicDatabase): MusicalAgeDao = database.musicalAgeDao

    @Singleton
    @Provides
    fun provideSongMetadataSource(dao: MusicalAgeDao): SongMetadataSource =
        RoomSongMetadataSource(dao)

    @Singleton
    @Provides
    fun provideMusicalAgeEngine(): MusicalAgeEngine = MusicalAgeEngine()

    @Singleton
    @Provides
    fun provideMetadataTransport(): MetadataTransport =
        KtorMetadataTransport(MusicBrainzUserAgent.VALUE)

    @Singleton
    @Provides
    fun provideEvidenceCache(@ApplicationContext context: Context): RecordingEvidenceCache =
        FileRecordingEvidenceCache(context.noBackupFilesDir)

    @Singleton
    @Provides
    fun provideManualEvidenceRepository(
        @ApplicationContext context: Context,
    ): ManualEvidenceRepository = ManualEvidenceRepository(context.noBackupFilesDir)

    @Singleton
    @Provides
    fun provideEnrichmentService(
        transport: MetadataTransport,
        metadataSource: SongMetadataSource,
        cache: RecordingEvidenceCache,
    ): MusicBrainzEnrichmentService = MusicBrainzEnrichmentService(
        transport = transport,
        metadataSource = metadataSource,
        cache = cache,
    )

    @Singleton
    @Provides
    fun provideMusicalAgeRepository(
        dao: MusicalAgeDao,
        engine: MusicalAgeEngine,
    ): MusicalAgeRepository = MusicalAgeRepository(dao, engine)
}
