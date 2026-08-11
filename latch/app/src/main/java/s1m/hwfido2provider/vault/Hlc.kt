package s1m.hwfido2provider.vault

import kotlinx.serialization.Serializable

/**
 * A hybrid logical clock.
 *
 * Wall time alone silently loses edits when two devices disagree about the time,
 * which they do. [counter] breaks ties inside a millisecond and keeps the clock
 * monotonic even when the system clock jumps backwards; [node] breaks ties
 * between devices, so the ordering is total and every device computes the same
 * one.
 *
 * This is deliberately *not* how concurrency is detected. Syncthing's version
 * vectors do that causally, and a conflict file appearing is the signal — see
 * VAULT.md. The clock has only two jobs: pick a winner once a conflict exists,
 * and order history.
 */
@Serializable
data class Hlc(val wall: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        if (wall != other.wall) return wall.compareTo(other.wall)
        if (counter != other.counter) return counter.compareTo(other.counter)
        return node.compareTo(other.node)
    }

    companion object {
        val ZERO = Hlc(0L, 0, "")
    }
}

/**
 * Issues clocks for one device. [node] must be stable for the life of the
 * install — a device that changes its node id stops being able to break ties
 * consistently with its own past writes.
 */
class Clock(
    private val node: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var lastWall = 0L
    private var counter = 0

    @Synchronized
    fun tick(): Hlc {
        val physical = now()
        if (physical > lastWall) {
            lastWall = physical
            counter = 0
        } else {
            // Clock stood still or went backwards. Counting keeps us monotonic
            // rather than issuing a stamp we have already used.
            counter++
        }
        return Hlc(lastWall, counter, node)
    }

    /**
     * Advance past a clock we have seen, so our next [tick] outranks it.
     *
     * Called when reading an entry another device wrote. Without it, a device
     * whose clock is behind writes stamps that lose every comparison, and its
     * edits look stale forever.
     */
    @Synchronized
    fun observe(seen: Hlc) {
        if (seen.wall > lastWall) {
            lastWall = seen.wall
            counter = seen.counter
        } else if (seen.wall == lastWall && seen.counter > counter) {
            counter = seen.counter
        }
    }
}
