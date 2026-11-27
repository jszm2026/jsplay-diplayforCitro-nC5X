package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L
    @Volatile var maxUiToExecutorNs = 0L
    @Volatile var maxExecutorWaitNs = 0L
    @Volatile var maxSocketSendNs = 0L
    @Volatile var maxEventToFrameNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    fun onTouchTiming(
        inputEventNs: Long,
        executorStartNs: Long,
        socketSendStartNs: Long,
        socketSendEndNs: Long,
    ) {
        maxUiToExecutorNs = maxOf(maxUiToExecutorNs, executorStartNs - inputEventNs)
        maxExecutorWaitNs = maxOf(maxExecutorWaitNs, socketSendStartNs - executorStartNs)
        maxSocketSendNs = maxOf(maxSocketSendNs, socketSendEndNs - socketSendStartNs)
    }

    fun onNextFrameAfterTouch(nowNs: Long) {
        val touch = pendingTouchNs
        if (touch != 0L) maxEventToFrameNs = maxOf(maxEventToFrameNs, nowNs - touch)
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        maxEventToFrameNs = maxOf(maxEventToFrameNs, nowNs - touch)
        return nowNs - touch
    }
}
