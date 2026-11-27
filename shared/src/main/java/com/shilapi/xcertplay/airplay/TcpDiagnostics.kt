package com.shilapi.xcertplay.airplay

import java.io.File

internal data class TcpCounterSnapshot(val values: Map<String, Long>) {
    fun absoluteLine(): String = render(values.mapValues { (_, value) -> value.toString() })

    fun deltaFrom(previous: TcpCounterSnapshot): String {
        if (values.isEmpty()) return "unavailable"
        val delta = linkedMapOf<String, String>()
        for (name in values.keys.sorted()) {
            val current = values[name] ?: continue
            val before = previous.values[name]
            delta[name] = when {
                before == null -> "baseline-missing"
                current >= before -> "+${current - before}"
                else -> "counter-reset(current=$current)"
            }
        }
        return render(delta)
    }

    private fun render(items: Map<String, String>): String =
        if (items.isEmpty()) "unavailable" else items.entries.joinToString(",") { "${it.key}=${it.value}" }
}

/** Reads device-wide TCP counters without depending on a vendor-specific netlink API. */
internal object TcpDiagnostics {
    private val interesting = setOf(
        "RetransSegs",
        "InErrs",
        "OutRsts",
        "TCPTimeouts",
        "TCPLossProbes",
        "TCPFastRetrans",
        "TCPRetransFail",
    )

    fun snapshot(): TcpCounterSnapshot {
        val values = linkedMapOf<String, Long>()
        readTable("/proc/net/snmp", values)
        readTable("/proc/net/netstat", values)
        return TcpCounterSnapshot(values)
    }

    fun parse(snmpLines: List<String>, netstatLines: List<String>): TcpCounterSnapshot {
        val values = linkedMapOf<String, Long>()
        parseTables(snmpLines, values)
        parseTables(netstatLines, values)
        return TcpCounterSnapshot(values)
    }

    private fun readTable(path: String, values: MutableMap<String, Long>) {
        val lines = runCatching { File(path).readLines() }.getOrElse { return }
        parseTables(lines, values)
    }

    private fun parseTables(lines: List<String>, values: MutableMap<String, Long>) {
        var index = 0
        while (index + 1 < lines.size) {
            val header = lines[index].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            val data = lines[index + 1].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            if (header.isNotEmpty() && header[0] == data.firstOrNull() && header.size == data.size) {
                for (column in 1 until header.size) {
                    val name = header[column]
                    if (name in interesting) data[column].toLongOrNull()?.let { values[name] = it }
                }
                index += 2
            } else {
                index++
            }
        }
    }
}

/** Produces comparable device-wide deltas at the existing five-second receive-stat cadence. */
internal class TcpCounterWindow(
    private val read: () -> TcpCounterSnapshot = TcpDiagnostics::snapshot,
) {
    private var previous = read()

    fun baseline(): String = previous.absoluteLine()

    @Synchronized fun nextDelta(): String {
        val current = read()
        val result = current.deltaFrom(previous)
        previous = current
        return result
    }
}
