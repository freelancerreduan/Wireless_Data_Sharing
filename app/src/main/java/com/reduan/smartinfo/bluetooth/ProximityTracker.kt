package com.reduan.smartinfo.bluetooth

/** Approximate proximity bands. RSSI cannot measure exact distance — labels are always "approx.". */
enum class Proximity(val label: String) {
    VERY_NEAR("Very near (approx.)"),
    NEAR("Near (approx.)"),
    ROOM("Same room (approx.)"),
    FAR("Far / weak signal"),
}

data class ProximityConfig(
    val veryNearRssi: Int = -55,
    val nearRssi: Int = -70,
    val roomRssi: Int = -85,
    /** A device must be heard consistently for this long before it can be classed VERY_NEAR. */
    val stableWindowMs: Long = 2_000,
    /** Tolerance below the threshold for individual samples inside the stable window. */
    val stabilityToleranceDb: Int = 6,
    val expiryMs: Long = 10_000,
)

class ProximityTracker(var config: ProximityConfig = ProximityConfig()) {
    private class Track(val firstSeen: Long) {
        val samples = ArrayDeque<Pair<Long, Int>>()
        var lastSeen = firstSeen
    }

    private val tracks = HashMap<String, Track>()

    @Synchronized
    fun add(id: String, rssi: Int, now: Long) {
        val t = tracks.getOrPut(id) { Track(now) }
        t.samples.addLast(now to rssi)
        t.lastSeen = now
        val keepFrom = now - maxOf(config.stableWindowMs * 2, 4_000)
        while (t.samples.isNotEmpty() && t.samples.first().first < keepFrom) t.samples.removeFirst()
    }

    @Synchronized
    fun smoothedRssi(id: String): Int? = tracks[id]?.samples?.map { it.second }?.let(::median)

    @Synchronized
    fun classify(id: String, now: Long): Proximity? {
        val t = tracks[id] ?: return null
        val med = median(t.samples.map { it.second }) ?: return null
        val c = config
        val window = t.samples.filter { it.first >= now - c.stableWindowMs }
        val stable = now - t.firstSeen >= c.stableWindowMs &&
            window.size >= 3 &&
            window.all { it.second >= c.veryNearRssi - c.stabilityToleranceDb }
        return when {
            med >= c.veryNearRssi && stable -> Proximity.VERY_NEAR
            med >= c.nearRssi -> Proximity.NEAR
            med >= c.roomRssi -> Proximity.ROOM
            else -> Proximity.FAR
        }
    }

    /** Removes devices not heard recently; returns their ids. */
    @Synchronized
    fun prune(now: Long): List<String> {
        val gone = tracks.filterValues { now - it.lastSeen > config.expiryMs }.keys.toList()
        gone.forEach { tracks.remove(it) }
        return gone
    }

    @Synchronized
    fun ids(): Set<String> = tracks.keys.toSet()

    @Synchronized
    fun clear() = tracks.clear()

    private fun median(v: List<Int>): Int? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
