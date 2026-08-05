package com.mandar.echo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lifecycle of a chunk:
 *
 *   RECORDING ──► PENDING ──► TRANSCRIBING ──► DONE      (audio deleted)
 *                    │              │        └► SILENT   (audio deleted, no speech)
 *                    │              └────────► FAILED    (audio KEPT for retry)
 *                    └──────────────────────► DISCARDED  (user deleted)
 *
 * Audio is only ever unlinked from a terminal state we can prove: DONE or SILENT.
 * FAILED deliberately keeps the WAV so nothing is lost irrecoverably.
 */
enum class ChunkStatus { RECORDING, PENDING, TRANSCRIBING, DONE, SILENT, FAILED, DISCARDED }

@Entity(
    tableName = "chunks",
    indices = [Index("status"), Index("startedAt")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch ms of the chunk's first sample. */
    val startedAt: Long,
    /** Epoch ms of the chunk's last sample; null while still recording. */
    val endedAt: Long? = null,
    /** Absolute path to the WAV, or null once the audio has been deleted. */
    val filePath: String? = null,
    val sampleCount: Long = 0,
    val status: ChunkStatus = ChunkStatus.RECORDING,
    val attempts: Int = 0,
    val audioDeleted: Boolean = false,
    val error: String? = null,
    /** Wall-clock ms spent in whisper; drives the realtime-factor gauge. */
    val transcribeMs: Long? = null,
    /** Fraction of frames the VAD judged to contain speech, 0f..1f. */
    val speechRatio: Float = 0f,
    val wordCount: Int = 0,
) {
    val durationMs: Long get() = sampleCount * 1000L / 16_000L
}

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("chunkId"), Index("startMs")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    /** Absolute epoch ms, already offset by the parent chunk's start. */
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** ISO code whisper detected for the parent chunk: "en", "hi", "mr", ... */
    val language: String,
)

@Entity(tableName = "summaries")
data class SummaryEntity(
    /** LocalDate.toEpochDay() of the day being summarised. */
    @PrimaryKey val dayEpochDay: Long,
    val generatedAt: Long,
    val headline: String,
    val bodyMarkdown: String,
    @ColumnInfo(name = "statsJson") val statsJson: String,
)
