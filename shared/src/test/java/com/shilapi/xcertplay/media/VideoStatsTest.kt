package com.shilapi.xcertplay.media

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoStatsTest {
    @Test
    fun reportsOneWindowAndKeepsReceivedAndSurfaceCountsSeparate() {
        var nowNs = 0L
        val stats = VideoStats(nanoTime = { nowNs })
        stats.onReceived(1_000)
        stats.onRendered()

        assertNull(stats.logIfDue())
        nowNs = 5_000_000_000L
        val line = stats.logIfDue()

        assertTrue(line.orEmpty().contains("rx=0.2fps"))
        assertTrue(line.orEmpty().contains("shown=0.2fps"))
        assertNull(stats.logIfDue())

        nowNs = 10_000_000_000L
        stats.onReceived(500)
        stats.onRendered()
        val nextWindow = stats.logIfDue()
        assertTrue(nextWindow.orEmpty().contains("rx=0.2fps"))
        assertTrue(nextWindow.orEmpty().contains("shown=0.2fps"))
    }
}
