package com.mandar.echo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lifecycle of a chunk:
 *
 *   RECORDING ─► PENDING ─► TRANSCRIBING ─► DONE       (audio released only if proven)
 *        │           ▲            │       └► SILENT    (no speech; same proof required)
 *        │           │            ├───────► FAILED     (audio KEPT, exits on user retry)
 *        │           └── park ────┘
 *        └─ crashed mid-write ──► PENDING, or DISCARDED if the WAV is gone
 *
 *   FAILED ─► PENDING       user tapped retry and the WAV is still there
 *   FAILED ─► DISCARDED     the WAV is gone, so retrying can only fail forever
 *
 * TRANSCRIBING is a *lease*, not a state a chunk can be stranded in: [claimedAt]
 * records when it was taken, so a claim older than the watchdog cutoff is provably
 * abandoned and can be recovered. Before that column existed, a "TRANSCRIBING for
 * more than N minutes" query could not be written at all, and a hang that spared
 * the process was never recovered.
 *
 * Audio is unlinked from exactly one place, under one guard — see
 * TranscriptionPipeline.releaseAudioIfProven. Storing a transcript is not on its
 * own sufficient proof that the audio is expendable.
 */
enum class ChunkStatus { RECORDING, PENDING, TRANSCRIBING, DONE, SILENT, FAILED, DISCARDED }

/** Which engine produced a chunk's stored transcript. Persisted so a degraded chunk stays findable. */
object TranscriptSource {
    /** IndicConformer via the user's server: 1.00 word recall on both Hindi and Marathi. */
    const val CLOUD = "cloud"

    /** On-device Whisper: measured 0.23 Hindi and 0.00 Marathi. Provisional whenever the cloud is configured. */
    const val DEVICE = "device"
}

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
    /** Wall-clock ms spent transcribing; drives the realtime-factor gauge. */
    val transcribeMs: Long? = null,
    /** Fraction of frames the VAD judged to contain speech, 0f..1f. */
    val speechRatio: Float = 0f,
    val wordCount: Int = 0,

    /**
     * When the current TRANSCRIBING lease was taken, or null if unclaimed.
     *
     * Also the lease token: a terminal write is conditional on it, so a worker
     * whose chunk was requeued underneath it (Doze stretched a Whisper run past
     * the watchdog cutoff) commits nothing instead of duplicating the day.
     */
    val claimedAt: Long? = null,

    /**
     * Epoch ms before which this chunk must not be claimed.
     *
     * This is the whole answer to head-of-line blocking. A chunk waiting on a cold
     * or unreachable server parks here and yields the worker, instead of holding
     * the queue while eighteen others pile up behind it.
     */
    @ColumnInfo(defaultValue = "0") val notBefore: Long = 0,

    /** Total claim entries. Monotonic; diagnostic only. */
    @ColumnInfo(defaultValue = "0") val claims: Int = 0,

    /**
     * Claims that ended with no outcome recorded at all — the process died mid-run.
     *
     * Counted separately from [claims] on purpose: a deliberate park is a normal
     * exit and must never look like a crash. `attempts` cannot see a crash loop
     * (it is only bumped from a catch block that a hard kill never reaches), so
     * without this an OOM in the JNI layer requeues forever.
     */
    @ColumnInfo(defaultValue = "0") val abandonedClaims: Int = 0,

    /** Consecutive transient failures. Drives backoff; never decides FAILED. */
    @ColumnInfo(defaultValue = "0") val transientFailures: Int = 0,

    /** [TranscriptSource] of the stored transcript, or null if nothing is stored yet. */
    val transcriptSource: String? = null,

    /** Voiced ms the gate found. The denominator of the coverage check. */
    @ColumnInfo(defaultValue = "0") val voicedMs: Long = 0,

    /**
     * Voiced ms actually covered by a stored transcript.
     *
     * Below [voicedMs] means part of the chunk was never transcribed — one piece
     * of a multi-piece upload was rejected, say. A non-zero word count is not
     * proof of completeness, and there was previously nowhere to record a hole.
     */
    @ColumnInfo(defaultValue = "0") val coveredMs: Long = 0,

    /**
     * Why the audio is being kept despite a terminal status, or null if it is safe
     * to release. Non-null marks the chunk re-transcribable.
     */
    val audioHold: String? = null,
) {
    val durationMs: Long get() = sampleCount * 1000L / 16_000L
}

/** Where a piece of a chunk's cloud upload has got to. Terminal: COMPLETED, REJECTED. */
enum class CloudJobState { NEW, SUBMITTED, COMPLETED, REJECTED }

/**
 * One server-sized piece of one chunk's voiced audio, and the job the server is
 * running for it.
 *
 * Durable because the alternative is a state machine that lives on a coroutine's
 * stack: parking a chunk would forfeit work the server has already been paid to
 * do, and every resume would re-upload megabytes of audio it is still holding.
 */
@Entity(
    tableName = "cloud_jobs",
    primaryKeys = ["chunkId", "pieceIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("chunkId")],
)
data class CloudJobEntity(
    val chunkId: Long,
    val pieceIndex: Int,
    /** Offset into the *compacted* voiced stream, not the original recording. */
    val offsetMs: Long,
    val durationMs: Long,
    /**
     * The language code actually posted, frozen at first submit.
     *
     * Re-reading the setting on resume would let a language change during a park
     * label already-stored Hindi text as Marathi, with the audio then deleted and
     * no way to notice.
     */
    val languageSent: String,
    /**
     * Identifies the voiced stream this piece was cut from.
     *
     * The gate is re-run from the WAV on every resume. If it produces a different
     * stream, these offsets no longer mean anything and the rows are discarded
     * rather than reused against audio they were not cut from.
     */
    val streamFingerprint: Long,
    val jobId: String? = null,
    val state: CloudJobState = CloudJobState.NEW,
    /** Non-null once COMPLETED. May legitimately be "": the model heard no words. */
    val transcript: String? = null,
    val error: String? = null,
    /** Consecutive resubmits after a JobGone. Reset by any accepted submit. */
    val resubmits: Int = 0,
    val submittedAt: Long = 0,
)

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
    /** ISO code of the language this was transcribed as: "en", "hi", "mr", ... */
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
    /**
     * True when chunks in the day were still queued at generation time.
     *
     * A summary written over an un-drained queue reports hours of speech as
     * silence, so it says so, and is rebuilt once the day settles.
     */
    @ColumnInfo(defaultValue = "0") val provisional: Boolean = false,
)
