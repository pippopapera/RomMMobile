package com.rommmobile.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

class EnumConverters {
    @TypeConverter fun downloadStateToString(v: DownloadState): String = v.name
    @TypeConverter fun stringToDownloadState(v: String): DownloadState = runCatching { DownloadState.valueOf(v) }.getOrDefault(DownloadState.FAILED)
    @TypeConverter fun downloadKindToString(v: DownloadKind): String = v.name
    @TypeConverter fun stringToDownloadKind(v: String): DownloadKind = runCatching { DownloadKind.valueOf(v) }.getOrDefault(DownloadKind.ROM)
    @TypeConverter fun mappingSourceToString(v: MappingSource): String = v.name
    @TypeConverter fun stringToMappingSource(v: String): MappingSource = runCatching { MappingSource.valueOf(v) }.getOrDefault(MappingSource.PRESET)
    @TypeConverter fun collectionKindToString(v: CollectionKind): String = v.name
    @TypeConverter fun stringToCollectionKind(v: String): CollectionKind = runCatching { CollectionKind.valueOf(v) }.getOrDefault(CollectionKind.USER)
}

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        DownloadEntity::class,
        LocalFileEntity::class,
        FolderMappingEntity::class,
        CollectionEntity::class,
        RecentSearchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class RommDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun downloadDao(): DownloadDao
    abstract fun localFileDao(): LocalFileDao
    abstract fun folderMappingDao(): FolderMappingDao
    abstract fun collectionDao(): CollectionDao
    abstract fun recentSearchDao(): RecentSearchDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): RommDatabase =
        Room.databaseBuilder(context, RommDatabase::class.java, "romm.db")
            // No destructive fallback on upgrade: the download queue and the USER folder
            // overrides must survive updates, so every version bump needs a real migration.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun platformDao(db: RommDatabase): PlatformDao = db.platformDao()
    @Provides fun romDao(db: RommDatabase): RomDao = db.romDao()
    @Provides fun downloadDao(db: RommDatabase): DownloadDao = db.downloadDao()
    @Provides fun localFileDao(db: RommDatabase): LocalFileDao = db.localFileDao()
    @Provides fun folderMappingDao(db: RommDatabase): FolderMappingDao = db.folderMappingDao()
    @Provides fun collectionDao(db: RommDatabase): CollectionDao = db.collectionDao()
    @Provides fun recentSearchDao(db: RommDatabase): RecentSearchDao = db.recentSearchDao()
}
