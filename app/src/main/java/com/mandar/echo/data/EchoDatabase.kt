package com.mandar.echo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun toStatus(value: String): ChunkStatus = ChunkStatus.valueOf(value)
    @TypeConverter fun fromStatus(status: ChunkStatus): String = status.name
    @TypeConverter fun toCloudJobState(value: String): CloudJobState = CloudJobState.valueOf(value)
    @TypeConverter fun fromCloudJobState(state: CloudJobState): String = state.name
}

/**
 * 1 → 2: the transcription queue becomes a durable state machine.
 *
 * Every added column carries a SQL `DEFAULT` matching the `@ColumnInfo` on the
 * entity. Kotlin default values produce no SQL default, and that mismatch is the
 * classic way a hand-written migration passes here and fails Room's identity check
 * on a real device.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `claimedAt` INTEGER")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `notBefore` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `claims` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `abandonedClaims` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `transientFailures` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `transcriptSource` TEXT")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `voicedMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `coveredMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chunks` ADD COLUMN `audioHold` TEXT")

        db.execSQL("ALTER TABLE `summaries` ADD COLUMN `provisional` INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cloud_jobs` (
                `chunkId` INTEGER NOT NULL,
                `pieceIndex` INTEGER NOT NULL,
                `offsetMs` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `languageSent` TEXT NOT NULL,
                `streamFingerprint` INTEGER NOT NULL,
                `jobId` TEXT,
                `state` TEXT NOT NULL,
                `transcript` TEXT,
                `error` TEXT,
                `resubmits` INTEGER NOT NULL,
                `submittedAt` INTEGER NOT NULL,
                PRIMARY KEY(`chunkId`, `pieceIndex`),
                FOREIGN KEY(`chunkId`) REFERENCES `chunks`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_jobs_chunkId` ON `cloud_jobs` (`chunkId`)")

        // Any chunk stranded mid-transcription by the old code is claimable again
        // under the new machine rather than invisible in the queue forever.
        db.execSQL("UPDATE `chunks` SET status = 'PENDING' WHERE status = 'TRANSCRIBING'")
    }
}

@Database(
    entities = [
        ChunkEntity::class,
        SegmentEntity::class,
        SummaryEntity::class,
        CloudJobEntity::class,
    ],
    version = 2,
    // Exported to app/schemas/ and committed. fallbackToDestructiveMigration is
    // deliberately absent below, so a wrong migration does not quietly wipe a
    // database -- it crashes the app permanently, on a stranger's phone, with
    // their recordings inside it. Without the export there is no golden schema
    // to diff a new version against and MIGRATION_2_3 would be written by eye;
    // with it, MigrationTestHelper can open a real v2 database and migrate it.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun segmentDao(): SegmentDao
    abstract fun summaryDao(): SummaryDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun cloudJobDao(): CloudJobDao

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
                .addMigrations(MIGRATION_1_2)
                // No fallbackToDestructiveMigration. Shipping a migration while
                // leaving that in place means any mistake in it silently wipes
                // every transcript the user has -- the one unrecoverable outcome
                // in this product. A crash on open is recoverable; a wipe is not.
                .build()
                .also { instance = it }
        }
    }
}
