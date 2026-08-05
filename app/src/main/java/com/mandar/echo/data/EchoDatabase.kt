package com.mandar.echo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun toStatus(value: String): ChunkStatus = ChunkStatus.valueOf(value)
    @TypeConverter fun fromStatus(status: ChunkStatus): String = status.name
}

@Database(
    entities = [ChunkEntity::class, SegmentEntity::class, SummaryEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun segmentDao(): SegmentDao
    abstract fun summaryDao(): SummaryDao
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile private var instance: EchoDatabase? = null

        fun get(context: Context): EchoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EchoDatabase::class.java,
                "echo.db",
            )
                // Foreign keys drive the segment cascade when a chunk is deleted.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
