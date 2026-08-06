package com.mandar.echo.audio

import android.content.Context
import android.os.StatFs

/**
 * The one place Echo measures free space, and the thresholds keyed to it.
 *
 * Shared because the recorder and the transcription pipeline were denominated in
 * different currencies: the recorder pauses on *free space*, while the pipeline's
 * escape valve fired on an absolute count of *bytes Echo is holding*. On any phone
 * with less than about 2 GB free — which the app explicitly supports, since it
 * records down to [MIN_FREE_BYTES] — the recorder's pause always arrived first, and
 * the valve that exists to prevent it could never open. Recording then stopped for
 * exactly the reason the valve was written to avoid.
 */
object DiskSpace {

    /** Recording pauses below this much free space, and resumes above [RESUME_FREE_BYTES]. */
    const val MIN_FREE_BYTES = 1_000_000_000L
    const val RESUME_FREE_BYTES = 1_500_000_000L

    /**
     * Free space below which the pipeline stops holding audio back.
     *
     * The same number the recorder resumes at, so the valve is open for exactly the
     * band in which recording is paused or about to be, and shut once there is room
     * to record comfortably again.
     */
    const val PRESSURE_FREE_BYTES = RESUME_FREE_BYTES

    /**
     * Held audio below which the free-space arm means nothing.
     *
     * A phone can sit at 1.2 GB free because of everything else on it. Releasing a
     * hoard smaller than the gap the recorder has to climb to resume ([MIN_FREE_BYTES]
     * to [RESUME_FREE_BYTES]) cannot lift the pause, so it would buy nothing and
     * spend audio for it. Below this the honest answer is the low-storage notice.
     */
    const val RELIEVABLE_BYTES = RESUME_FREE_BYTES - MIN_FREE_BYTES

    /**
     * @return [Long.MAX_VALUE] if the volume cannot be stat-ed. Both callers act on
     *   a low reading — one stops recording, the other stops keeping audio — so a
     *   failed measurement must not be able to trigger either.
     */
    fun freeBytes(context: Context): Long =
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
}
