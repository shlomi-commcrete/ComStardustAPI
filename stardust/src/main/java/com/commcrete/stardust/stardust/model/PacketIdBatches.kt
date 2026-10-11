package com.commcrete.stardust.stardust.model

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Received packets that belong together — the parts of one long text, the audio packets of one
 * PTT transmission — resolve their sender / group / chat once, from the first packet, and the
 * rest of the same batch reuse it ([StardustPackage.adoptBatchIds]). Every packet used to make
 * the lookup itself: two blocking database round trips per audio packet, many per second.
 *
 * Reuse is only ever within ONE batch, never across messages: a batch is keyed by the packet's
 * source and destination and its [Kind], and ends
 *  - explicitly ([end]) — a text's LAST part;
 *  - or after [idleMs] with no packet of it — the gap that, for that kind, means the sender has
 *    moved on to a new message or a new press (see the callers for each kind's window).
 * The next packet after either starts a new batch with a fresh lookup, so a change in groups or
 * chats is picked up by the next message.
 *
 * Considered keeping this in GroupsUtils (stateless resolution helpers — no place for per-batch
 * state) and in IncomingTextAssembler (texts only, and PTT needs the same).
 */
internal class PacketIdBatches(
    private val resolve: (sourceId: String, destinationId: String) -> StardustPackage.ResolvedIds,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    enum class Kind { TEXT, PTT }

    private data class Key(val sourceId: String, val destinationId: String, val kind: Kind)

    private class Batch(val ids: StardustPackage.ResolvedIds, @Volatile var lastSeenMs: Long)

    private val open = ConcurrentHashMap<Key, Batch>()

    /** The ids for a packet of this batch: the batch's own, or a fresh lookup that starts one. */
    fun idsFor(sourceId: String, destinationId: String, kind: Kind, idleMs: Long): StardustPackage.ResolvedIds {
        val key = Key(sourceId, destinationId, kind)
        val time = now()
        open[key]?.takeIf { time - it.lastSeenMs <= idleMs }?.let { batch ->
            batch.lastSeenMs = time
            return batch.ids
        }
        val ids = resolve(sourceId, destinationId)
        open[key] = Batch(ids, time)
        return ids
    }

    /** The batch is over (a text's LAST part): the next packet starts a new one. */
    fun end(sourceId: String, destinationId: String, kind: Kind) {
        open.remove(Key(sourceId, destinationId, kind))
    }

    /** Every batch is over — the link went away. */
    fun clear() = open.clear()

    companion object {
        val shared = PacketIdBatches(resolve = StardustPackage::resolveIds)
    }
}
