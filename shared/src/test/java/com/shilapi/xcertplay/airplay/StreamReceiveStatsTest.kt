package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun separatesSocketWaitFromLocalProcessingAndTracksSequenceWrap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int) {
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            clock += 200_000L
            stats.processed()
        }
        packet(65535)
        packet(0)
        packet(3) // two missing, then one late packet arrives
        packet(2)
        packet(4)
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("readMaxMs=400 processMaxUs=200 seqForwardGaps=2 lateOrDuplicate=1"))
        assertTrue(output.single().contains("packets=5 bytes=500"))
    }

    @Test fun reportResetsWindowButRetainsSequenceContinuity() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock = 5_000_000_000L
        stats.received(10, 1)
        stats.processed()
        stats.reading()
        stats.received(20, 3)
        stats.processed()
        stats.flush(true)
        assertEquals(2, output.size)
        assertTrue(output.last().contains("packets=1 bytes=20 readMaxMs=0"))
        assertTrue(output.last().contains("seqForwardGaps=1"))
    }

    @Test fun separatesFirstByteAssemblyAndQueueWaitFromReadTime() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        stats.reading()
        clock = 100_000_000L
        stats.firstByte()
        clock = 300_000_000L
        stats.received(1_024)
        stats.queued(100_000_000L)
        clock = 500_000_000L
        stats.processed()
        stats.flush(ended = true)

        assertTrue(output.single().contains("readMaxMs=300"))
        assertTrue(output.single().contains("firstByteMaxMs=100 assemblyMaxMs=200"))
        assertTrue(output.single().contains("queueWaitMaxMs=200 frameSizeMaxBytes=1024"))
    }

    @Test fun addsTcpDeviceCounterDeltasToEachReceiveWindow() {
        val snmp = listOf(
            "Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts InCsumErrors",
            "Tcp: 1 200 120000 -1 0 0 0 0 1 10 10 5 0 0 0",
        )
        val netstat = listOf(
            "TcpExt: TCPFastRetrans TCPTimeouts TCPLossProbes TCPRetransFail",
            "TcpExt: 2 3 4 0",
        )
        var current = TcpDiagnostics.parse(snmp, netstat)
        val window = TcpCounterWindow { current }
        assertTrue(window.baseline().contains("RetransSegs=5"))

        val nextSnmp = listOf(snmp[0], snmp[1].replace(" 5 0", " 9 0"))
        val nextNetstat = listOf(netstat[0], "TcpExt: 3 4 6 0")
        current = TcpDiagnostics.parse(nextSnmp, nextNetstat)
        assertEquals(
            "InErrs=+0,OutRsts=+0,RetransSegs=+4,TCPFastRetrans=+1," +
                "TCPLossProbes=+2,TCPRetransFail=+0,TCPTimeouts=+1",
            window.nextDelta(),
        )
    }

    @Test fun separatesEmptyProtocolMessagesFromVideoFrameTiming() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        stats.reading()
        clock = 100_000_000L
        stats.firstByte()
        clock = 1_100_000_000L
        stats.received(128, kind = "empty", bodySize = 0)
        stats.processed()

        stats.reading()
        clock += 10_000_000L
        stats.firstByte()
        clock += 20_000_000L
        stats.received(4_128, kind = "frame", bodySize = 4_000)
        clock += 1_000_000L
        stats.processed()
        stats.flush(ended = true)

        val line = output.single()
        assertTrue(line.contains("videoFrames=1 emptyMessages=1"))
        assertTrue(line.contains("videoFrameFirstByteMaxMs=10"))
        assertTrue(line.contains("videoFrameAssemblyMaxMs=20"))
        assertTrue(line.contains("videoFrameBodyMaxBytes=4000"))
    }

    @Test fun reportsCounterResetInsteadOfNegativeDelta() {
        val before = TcpCounterSnapshot(mapOf("RetransSegs" to 12L))
        val after = TcpCounterSnapshot(mapOf("RetransSegs" to 2L))
        assertEquals("RetransSegs=counter-reset(current=2)", after.deltaFrom(before))
    }
}
