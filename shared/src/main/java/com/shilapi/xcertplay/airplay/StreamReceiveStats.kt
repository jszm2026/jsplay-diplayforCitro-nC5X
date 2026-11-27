package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val windowDetails: (() -> String)? = null,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var firstByteNs = 0L
    private var maxFirstByteWaitNs = 0L
    private var maxAssemblyNs = 0L
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var maxQueueWaitNs = 0L
    private var maxMessageSize = 0
    private var queueDrops = 0
    private var videoFrames = 0
    private var emptyMessages = 0
    private var configMessages = 0
    private var otherMessages = 0
    private var lastVideoFrameCompleteNs = 0L
    private var maxVideoFrameGapNs = 0L
    private var maxVideoFrameFirstByteNs = 0L
    private var maxVideoFrameAssemblyNs = 0L
    private var maxVideoFrameBodySize = 0
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0

    @Synchronized fun reading() {
        readStart = nowNs()
        firstByteNs = 0L
    }

    @Synchronized fun firstByte() {
        val now = nowNs()
        if (firstByteNs == 0L) {
            firstByteNs = now
            maxFirstByteWaitNs = maxOf(maxFirstByteWaitNs, now - readStart)
        }
    }

    @Synchronized fun received(
        size: Int,
        sequence: Int? = null,
        timestamp: Int? = null,
        kind: String? = null,
        bodySize: Int = size,
    ) {
        processingStart = nowNs()
        maxReadNs = maxOf(maxReadNs, processingStart - readStart)
        if (firstByteNs != 0L) maxAssemblyNs = maxOf(maxAssemblyNs, processingStart - firstByteNs)
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        maxMessageSize = maxOf(maxMessageSize, size)
        when (kind) {
            "frame" -> {
                videoFrames++
                if (firstByteNs != 0L) {
                    maxVideoFrameFirstByteNs = maxOf(maxVideoFrameFirstByteNs, firstByteNs - readStart)
                    maxVideoFrameAssemblyNs = maxOf(maxVideoFrameAssemblyNs, processingStart - firstByteNs)
                }
                if (lastVideoFrameCompleteNs != 0L) {
                    maxVideoFrameGapNs = maxOf(maxVideoFrameGapNs, processingStart - lastVideoFrameCompleteNs)
                }
                lastVideoFrameCompleteNs = processingStart
                maxVideoFrameBodySize = maxOf(maxVideoFrameBodySize, bodySize)
            }
            "empty" -> emptyMessages++
            "config" -> configMessages++
            null -> Unit
            else -> otherMessages++
        }
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    @Synchronized fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    @Synchronized fun queued(enqueuedNs: Long) {
        maxQueueWaitNs = maxOf(maxQueueWaitNs, nowNs() - enqueuedNs)
    }

    @Synchronized fun queueDropped() {
        queueDrops++
    }

    @Synchronized fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        val details = runCatching { windowDetails?.invoke().orEmpty() }.getOrDefault("")
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "firstByteMaxMs=${maxFirstByteWaitNs / 1_000_000} assemblyMaxMs=${maxAssemblyNs / 1_000_000} " +
            "queueWaitMaxMs=${maxQueueWaitNs / 1_000_000} frameSizeMaxBytes=$maxMessageSize queueDrops=$queueDrops " +
            "videoFrames=$videoFrames emptyMessages=$emptyMessages configMessages=$configMessages otherMessages=$otherMessages " +
            "videoFrameFirstByteMaxMs=${maxVideoFrameFirstByteNs / 1_000_000} " +
            "videoFrameAssemblyMaxMs=${maxVideoFrameAssemblyNs / 1_000_000} " +
            "videoFrameGapMaxMs=${maxVideoFrameGapNs / 1_000_000} " +
            "videoFrameBodyMaxBytes=$maxVideoFrameBodySize " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended" +
            if (details.isEmpty()) "" else " $details") }
        windowStart = now
        packets = 0
        bytes = 0
        maxReadNs = 0
        maxFirstByteWaitNs = 0
        maxAssemblyNs = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        maxQueueWaitNs = 0
        maxMessageSize = 0
        queueDrops = 0
        videoFrames = 0
        emptyMessages = 0
        configMessages = 0
        otherMessages = 0
        maxVideoFrameGapNs = 0
        maxVideoFrameFirstByteNs = 0
        maxVideoFrameAssemblyNs = 0
        maxVideoFrameBodySize = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

}
