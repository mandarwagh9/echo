package com.mandar.echo.stt

import android.util.Log
import com.mandar.echo.data.EchoDatabase
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.Settings
import org.json.JSONObject

private const val TAG = "BatchSync"

/**
 * Collects transcripts the batch pipeline has finished, for chunks this device
 * uploaded.
 *
 * The return leg of the batch backend. Chunks sit in `UPLOADED` holding their
 * audio until this finds their words; until then nothing about them is settled
 * and their WAV is not released.
 *
 * Asks by exact object name rather than by a time window. The device knows
 * precisely which chunks it is waiting for, and a name cannot drift, need an
 * index, or miss a transcript that arrived later than a window expected. Object
 * names are derived, never stored: `pending/<startedAt>.wav` is what the service
 * minted, so the chunk's own start time is the key.
 *
 * Nothing here fabricates a transcript. A chunk whose object is absent from the
 * reply is simply left alone — that is the ordinary case, because batching means
 * most polls happen before the answer exists.
 */
internal class BatchSync(
    private val db: EchoDatabase,
    private val postJson: suspend (url: String, apiKey: String, body: String) -> UploadReply,
) {

    private companion object {
        /** Well under the service's 200 cap, and one request is one round trip. */
        const val BATCH = 100
    }

    /** @return how many chunks were completed by this pass. */
    suspend fun run(cfg: Settings): Int {
        if (cfg.uploadUrl.isBlank() || cfg.uploadKey.isBlank()) return 0

        val waiting = runCatching { db.chunkDao().uploadedChunks(BATCH) }
            .getOrElse {
                Log.e(TAG, "could not read uploaded chunks", it)
                return 0
            }
        if (waiting.isEmpty()) return 0

        val byName = waiting.associateBy { "${it.startedAt}.wav" }
        val body = JSONObject().put(
            "objects",
            org.json.JSONArray(byName.keys.map { "pending/$it" }),
        ).toString()

        val reply = postJson("${cfg.uploadUrl}/v1/segments", cfg.uploadKey, body)
        if (reply.code != 200) {
            // Absolutely nothing is settled on a failed poll. The chunks keep
            // their audio and are asked about again next pass.
            Log.i(TAG, "segment poll returned ${reply.code}")
            return 0
        }

        val found = runCatching { JSONObject(reply.body).optJSONObject("segments") }
            .getOrNull() ?: return 0

        var completed = 0
        for (name in found.keys()) {
            val chunk = byName[name] ?: continue
            val rows = found.optJSONArray(name) ?: continue

            val segments = ArrayList<SegmentEntity>(rows.length())
            var words = 0
            var covered = 0L
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val text = row.optString("text").trim()
                if (text.isEmpty()) continue
                val startMs = row.optLong("startMs")
                val endMs = row.optLong("endMs")
                segments += SegmentEntity(
                    chunkId = chunk.id,
                    // Absolute epoch, from the chunk's own start plus the offset
                    // inside the file. This is why the raw chunk is uploaded
                    // rather than the compacted voiced stream: against a
                    // compacted stream these offsets mean nothing.
                    startMs = chunk.startedAt + startMs,
                    endMs = chunk.startedAt + endMs,
                    text = text,
                    language = row.optString("language").take(2).ifBlank { "en" },
                )
                words += text.split(Regex("\\s+")).count { it.isNotBlank() }
                covered += (endMs - startMs).coerceAtLeast(0)
            }

            runCatching {
                db.transcriptDao().commitRemote(
                    chunkDao = db.chunkDao(),
                    segmentDao = db.segmentDao(),
                    chunkId = chunk.id,
                    segments = segments,
                    wordCount = words,
                    coveredMs = covered,
                )
            }.onSuccess {
                completed += 1
                Log.i(TAG, "chunk ${chunk.id}: ${segments.size} segment(s) from the batch pipeline")
            }.onFailure {
                Log.e(TAG, "chunk ${chunk.id}: could not commit remote transcript", it)
            }
        }
        return completed
    }
}
