package com.commcrete.stardust.util

import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.SelfIdKind
import com.commcrete.stardust.room.new_db.internal.normalizeIdOrNull

/** One id the operator held over [fromMs] until [toMs], exclusive. */
data class SelfIdPeriod(val id: String, val kind: SelfIdKind, val fromMs: Long, val toMs: Long)

/**
 * The operator's ids over time, as one immutable snapshot: the current App ID and radio, and
 * the ones they replaced. Answers "was this message mine?" for history and display — a message
 * keeps its sender after an App ID or radio change. Live traffic and routing ask
 * [isMineNow] (current ids only), as [RegisteredUserUtils.isRegisteredUser] does.
 *
 * Not methods on [RegisterUser]: that is a mutable Gson DTO, changed in place by
 * SharedPreferencesUtil.updateAppUserBackup, and a snapshot read per message row off the main
 * thread must not change under its reader. Take one per list build, not one per row.
 */
data class SelfIdentity(
    val appId: String?,
    val deviceId: String?,
    val periods: List<SelfIdPeriod>,
) {
    private val byId: Map<String, List<SelfIdPeriod>> by lazy { periods.groupBy { it.id } }

    /** Whether [id] was one of the operator's ids at [atMs]. */
    fun wasMine(id: String?, atMs: Long): Boolean = held(id, atMs, kind = null)

    /** Whether [id] was the operator's radio at [atMs] — a radio-raised SOS is stored under it. */
    fun wasOwnRadio(id: String?, atMs: Long): Boolean = held(id, atMs, SelfIdKind.DEVICE_ID)

    /** Whether [id] is one of the operator's ids now. */
    fun isMineNow(id: String?): Boolean {
        val n = normalizeIdOrNull(id) ?: return false
        return n == appId || n == deviceId
    }

    private fun held(id: String?, atMs: Long, kind: SelfIdKind?): Boolean {
        val n = normalizeIdOrNull(id) ?: return false
        return byId[n].orEmpty().any { (kind == null || it.kind == kind) && atMs >= it.fromMs && atMs < it.toMs }
    }

    companion object {
        val NONE = SelfIdentity(null, null, emptyList())

        fun of(user: RegisterUser?): SelfIdentity {
            user ?: return NONE
            val appId = normalizeIdOrNull(user.appId)
            val deviceId = normalizeIdOrNull(user.deviceId)
            val periods = buildList {
                user.formerIds.orEmpty().forEach { f ->
                    // Entries Gson could not fully read (an unknown kind name) are skipped.
                    val id = normalizeIdOrNull(f.id) ?: return@forEach
                    val kind = f.kind ?: return@forEach
                    add(SelfIdPeriod(id, kind, f.fromMs ?: Long.MIN_VALUE, f.toMs))
                }
                appId?.let { add(SelfIdPeriod(it, SelfIdKind.APP_ID, user.appIdSinceMs ?: Long.MIN_VALUE, Long.MAX_VALUE)) }
                deviceId?.let { add(SelfIdPeriod(it, SelfIdKind.DEVICE_ID, user.deviceIdSinceMs ?: Long.MIN_VALUE, Long.MAX_VALUE)) }
            }
            return SelfIdentity(appId, deviceId, periods)
        }
    }
}
