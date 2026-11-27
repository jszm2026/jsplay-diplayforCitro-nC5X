package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VideoDecodeQueueTest {
    private val nowNs = 1_000_000_000L

    private fun frame(
        nalType: Int,
        receivedNs: Long = nowNs,
        size: Int = 6,
    ): VideoJob.Frame {
        val bytes = ByteArray(size.coerceAtLeast(6))
        bytes[0] = 0
        bytes[1] = 0
        bytes[2] = 0
        bytes[3] = 1
        bytes[4] = nalType.toByte()
        bytes[5] = 1
        return VideoJob.Frame(bytes, receivedNs, VideoCodec.H264)
    }

    @Test fun normalQueueEnqueuesAndDequeuesInOrder() {
        val queue = VideoDecodeQueue(nanoTime = { nowNs })
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        queue.offer(config)
        queue.offer(frame(0x65))
        queue.offer(frame(0x41))

        assertSame(config, queue.poll(0))
        assertEquals(0x65.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[4])
        assertEquals(0x41.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[4])
        assertEquals(0, queue.snapshot().frames)
    }

    @Test fun frameLimitDropsOldPicturesAndDoesNotKeepDependentPFrames() {
        val queue = VideoDecodeQueue(maxFrames = 2, nanoTime = { nowNs })
        queue.offer(frame(0x41, nowNs - 10_000_000L))
        queue.offer(frame(0x41, nowNs - 5_000_000L))
        queue.offer(frame(0x41))

        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
        assertTrue(queue.snapshot().overflowEvents > 0)
        assertTrue(queue.snapshot().droppedFrames >= 3)
    }

    @Test fun byteLimitTriggersResyncWithoutKeepingAnUnsafeSuffix() {
        val queue = VideoDecodeQueue(maxFrames = 6, maxBytes = 10, nanoTime = { nowNs })
        queue.offer(frame(0x41, size = 6))
        queue.offer(frame(0x41, size = 6))

        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
        assertTrue(queue.snapshot().overflowEvents > 0)
    }

    @Test fun oldestFrameAgeTriggersRecoveryAt160Ms() {
        var clock = nowNs
        val queue = VideoDecodeQueue(nanoTime = { clock })
        queue.offer(frame(0x41, receivedNs = nowNs))
        clock += 161_000_000L

        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun recentIdrKeepsIdrAndItsContinuousDependentSuffix() {
        val queue = VideoDecodeQueue(maxFrames = 3, nanoTime = { nowNs })
        queue.offer(frame(0x41, nowNs - 50_000_000L))
        queue.offer(frame(0x65, nowNs - 40_000_000L))
        queue.offer(frame(0x41, nowNs - 30_000_000L))
        queue.offer(frame(0x41, nowNs - 10_000_000L))

        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(0x65.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[4])
        assertEquals(0x41.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[4])
        assertEquals(0x41.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[4])
        assertNull(queue.poll(0))
    }

    @Test fun noValidIdrMeansQueueWaitsForANewIdr() {
        val queue = VideoDecodeQueue(maxFrames = 2, nanoTime = { nowNs })
        queue.offer(frame(0x41, nowNs - 20_000_000L))
        queue.offer(frame(0x41, nowNs - 10_000_000L))
        queue.offer(frame(0x41))

        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
        queue.offer(frame(0x65))
        assertTrue((queue.poll(0) as VideoJob.Frame).nalus[4].toInt() and 31 == 5)
    }

    @Test fun configAndSurfaceOrderIsKeptAcrossIdrRecovery() {
        val queue = VideoDecodeQueue(maxFrames = 3, nanoTime = { nowNs })
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1, 2))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(surface)
        queue.offer(frame(0x41, nowNs - 50_000_000L))
        queue.offer(frame(0x65, nowNs - 40_000_000L))
        queue.offer(frame(0x41, nowNs - 20_000_000L))
        queue.offer(frame(0x41))

        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(0x65.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[4])
        assertNotNull(queue.poll(0))
    }

    @Test fun explicitResyncClearsPicturesButKeepsControlJobs() {
        val queue = VideoDecodeQueue(nanoTime = { nowNs })
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(surface)
        queue.offer(frame(0x65))
        queue.offer(frame(0x41))
        queue.offer(VideoJob.Resync)

        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun queueStatsExposeHighWaterDropsAndOldestAge() {
        val queue = VideoDecodeQueue(maxFrames = 6, nanoTime = { nowNs })
        queue.offer(frame(0x65, nowNs - 50_000_000L))
        queue.offer(frame(0x41, nowNs - 20_000_000L))
        val stats = queue.snapshot()

        assertEquals(2, stats.frames)
        assertTrue(stats.bytes > 0)
        assertTrue(stats.oldestAgeMs >= 50)
        assertEquals(2, stats.highWater)
    }

    @Test fun fullOutputIsDrainedWhileRetryingInput() {
        var heldOutputs = 2
        var dequeues = 0
        var retries = 0
        val index = VideoInputPump.acquire(
            running = { true },
            drain = { if (heldOutputs > 0) heldOutputs-- },
            dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            onRetry = { retries++ },
        )
        assertEquals(0, index)
        assertEquals(2, dequeues)
        assertEquals(1, retries)
    }

    @Test fun inputWaitHasFiniteDeadlineAndStopsWhenFrameExpires() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true },
            drain = {},
            dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } },
            timeoutNs = 30,
        ))
        assertEquals(3, attempts)

        var expired = false
        attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true },
            drain = {},
            dequeue = { attempts++; -1 },
            expired = { expired },
            onRetry = { expired = true },
        ))
        assertEquals(1, attempts)
    }

    @Test fun shutdownCancelsInputWaitImmediately() {
        assertEquals(-1, VideoInputPump.acquire(
            running = { false },
            drain = { fail() },
            dequeue = { fail(); 0 },
        ))
    }

    @Test fun emptyPollTimesOutAndOfferWakesWaitingConsumer() {
        val queue = VideoDecodeQueue()
        val started = System.nanoTime()
        assertNull(queue.poll(20))
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(10))

        val ready = CountDownLatch(1)
        val result = AtomicReference<VideoJob?>()
        val consumer = Thread {
            ready.countDown()
            result.set(queue.poll(1_000))
        }
        consumer.start()
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        queue.offer(VideoJob.SurfaceChanged(null))
        consumer.join(1_000)

        assertFalse(consumer.isAlive)
        assertEquals(VideoJob.SurfaceChanged(null), result.get())
    }
}
