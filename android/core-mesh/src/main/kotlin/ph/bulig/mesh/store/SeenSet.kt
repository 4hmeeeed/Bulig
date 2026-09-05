package ph.bulig.mesh.store

import ph.bulig.mesh.model.PacketId

/**
 * The set of packet ids this device has already handled.
 *
 * This is the mechanism that stops loops. It is deliberately separate from the
 * packet store: a device may prune stored payloads to reclaim space while still
 * remembering that it has already seen a given packet, so a re-delivery is
 * suppressed rather than treated as new.
 */
interface SeenSet {
    fun contains(id: PacketId): Boolean
    fun add(id: PacketId)
    fun size(): Int
}

class InMemorySeenSet(
    /** Oldest entries are evicted first once this many ids are held. */
    private val capacity: Int = 10_000,
) : SeenSet {

    // Insertion-ordered so eviction is oldest-first.
    private val ids = LinkedHashSet<PacketId>()

    override fun contains(id: PacketId): Boolean = ids.contains(id)

    override fun add(id: PacketId) {
        if (ids.add(id) && ids.size > capacity) {
            val oldest = ids.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    override fun size(): Int = ids.size

    fun snapshot(): Set<PacketId> = ids.toSet()
}
