package com.mandar.echo.stt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CloudGate"

/**
 * One answer to "is the server worth trying right now", shared by every chunk.
 *
 * Without this the per-chunk machine rediscovers the same fact once per chunk: on
 * a captive-portal wifi, eighteen queued chunks each spend the full wake budget
 * learning that nothing answers, which is three quarters of an hour of wall clock
 * producing nothing while recording adds more chunks the whole time. A probe
 * outcome is a property of the network, not of a chunk.
 *
 * It also carries the two states that must never be per-chunk failures. Bad
 * credentials and a bad URL are configuration, and the only correct response to
 * configuration being wrong is to stop and say so with the audio untouched —
 * grinding a day of Marathi through Whisper at 0.00 word recall because a key was
 * rotated is strictly worse than doing nothing.
 */
class CloudGate(context: Context) {

    private companion object {
        /** Long enough that a dead network costs one probe per interval, short enough to recover fast. */
        const val DOWN_BACKOFF_MS = 60_000L
        const val DOWN_BACKOFF_MAX_MS = 5 * 60_000L
    }

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val downUntil = AtomicLong(0)
    private val downSince = AtomicLong(0)
    private val consecutiveDown = AtomicLong(0)
    private val halted = AtomicReference<String?>(null)

    /** Set once the cloud path is proven wrong-by-configuration; only a settings change clears it. */
    val haltReason: String? get() = halted.get()

    /** Epoch ms the server is next worth probing, or 0. */
    val retryServerAt: Long get() = downUntil.get()

    /** Epoch ms the server was first seen to be unreachable in the current outage, or 0. */
    val unreachableSince: Long get() = downSince.get()

    init {
        // Re-probe the moment the radio changes rather than waiting out a backoff
        // that was started on a network the phone has since left.
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = clearBackoff()
                    override fun onLost(network: Network) = Unit
                },
            )
        }.onFailure { Log.w(TAG, "no connectivity callback; falling back to timers", it) }
    }

    /** True when the system reports a network that has actually been validated end to end. */
    fun hasNetwork(): Boolean {
        val caps = runCatching {
            connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isMetered(): Boolean =
        runCatching { connectivity?.isActiveNetworkMetered }.getOrNull() ?: true

    /** Why the cloud path cannot run right now, or null if it can. */
    fun blockedReason(now: Long, wifiOnly: Boolean): String? = when {
        halted.get() != null -> halted.get()
        !hasNetwork() -> "waiting for a network"
        wifiOnly && isMetered() -> "waiting for wi-fi"
        now < downUntil.get() -> "server unreachable"
        else -> null
    }

    /** True when nothing is reachable at all, so a park must not spend the cloud's patience. */
    fun offline(): Boolean = !hasNetwork()

    fun noteReachable() {
        consecutiveDown.set(0)
        downUntil.set(0)
        downSince.set(0)
    }

    fun noteUnreachable(now: Long, reason: String) {
        val n = consecutiveDown.incrementAndGet()
        val backoff = (DOWN_BACKOFF_MS * n).coerceAtMost(DOWN_BACKOFF_MAX_MS)
        downUntil.set(now + backoff)
        downSince.compareAndSet(0, now)
        Log.w(TAG, "server unreachable ($reason); not retrying for ${backoff / 1000}s")
    }

    fun halt(reason: String) {
        if (halted.getAndSet(reason) == null) Log.e(TAG, "cloud path halted: $reason")
    }

    /** Called when the settings that could fix a halt change, and on a successful submit. */
    fun clearHalt() {
        if (halted.getAndSet(null) != null) Log.i(TAG, "cloud path re-enabled")
    }

    private fun clearBackoff() {
        consecutiveDown.set(0)
        downUntil.set(0)
    }
}
