package com.shilapi.xcertplay.media

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private val rendered = AtomicInteger()
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    fun onRendered() { rendered.incrementAndGet() }

    @Synchronized fun onRecovery() { recoveries++ }

    @Volatile private var nextWindowCheckNs = windowStartNs + WINDOW_NS

    fun logIfDue(): String? {
        val observedNow = nanoTime()
        if (observedNow < nextWindowCheckNs) return null
        return synchronized(this) {
            val now = nanoTime()
            val elapsedNs = now - windowStartNs
            if (elapsedNs < WINDOW_NS) return@synchronized null
            val renderedCount = rendered.getAndSet(0)
            if (received == 0 && renderedCount == 0 && touchSamples == 0) {
                windowStartNs = now
                nextWindowCheckNs = now + WINDOW_NS
                return@synchronized null
            }
            val seconds = elapsedNs / 1e9
            val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
            val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
                "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms " +
                "touchUiToExecutorMax=%dms touchExecutorWaitMax=%dms touchSocketSendMax=%dms " +
                "touchEventToNextVideoFrameMax=%dms").format(
                received / seconds, renderedCount / seconds, maxArrivalGapNs / 1_000_000,
                (bytes * 8 / 1000 / seconds).toLong(), recoveries,
                touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
                TouchLatencyProbe.maxUiToExecutorNs / 1_000_000,
                TouchLatencyProbe.maxExecutorWaitNs / 1_000_000,
                TouchLatencyProbe.maxSocketSendNs / 1_000_000,
                TouchLatencyProbe.maxEventToFrameNs / 1_000_000,
            )
            TouchLatencyProbe.maxSendNs = 0
            TouchLatencyProbe.maxUiToExecutorNs = 0
            TouchLatencyProbe.maxExecutorWaitNs = 0
            TouchLatencyProbe.maxSocketSendNs = 0
            TouchLatencyProbe.maxEventToFrameNs = 0
            Log.i(TAG, line)
            windowStartNs = now
            nextWindowCheckNs = now + WINDOW_NS
            received = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
            touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
            line
        }
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
