package com.commcrete.stardust.request_objects

import com.commcrete.stardust.room.new_db.internal.normalizeId
import com.google.gson.Gson

class RegisterUser (
    var displayName: String,
    private val _appId: String,
    private val _deviceId: String? = null,
) {

    var appId: String = normalizeId(_appId)
        set(value) { field = normalizeId(value) }

    var deviceId: String? = _deviceId?.let { normalizeId((it)) }
        set(value) { field = value ?.let { normalizeId(it) } }

    /**
     * Backup copy of the paired radio's display name. The primary copy is the
     * `bittel_device_name` preference; this one is read only when that comes back empty.
     * Maintained by [com.commcrete.stardust.util.SharedPreferencesUtil] — don't set directly.
     */
    var bittelName: String? = null

    /** Backup copy of the paired radio's MAC address (primary: the `bittel_device` preference). */
    var bittelMacAddress: String? = null

    /**
     * The operator's own contact icon — the same preset key a contact stores in
     * `ContactEntity.image`. Null means "not chosen", and also "not set on this instance":
     * [com.commcrete.stardust.util.SharedPreferencesUtil.setAppUser] carries the saved key over
     * onto a freshly built user that leaves it null, so rebuilding the user never drops it.
     */
    var iconKey: String? = null

    /*
     * Own-id history, so a message keeps its sender after the operator changes App ID or radio.
     * Maintained by SharedPreferencesUtil.setAppUser (takeHistoryFrom) — don't set directly.
     * All nullable: Gson builds this class without its constructor, so a field missing from JSON
     * written before these existed reads as null, which means "since forever, no former ids".
     * [formerIds] is an Array, not a List: Gson reads an array's element type without the
     * generic Signature attribute, which R8 may strip in the host build.
     */
    /** When the current [appId] took effect; null = held since before tracking began. */
    var appIdSinceMs: Long? = null
    /** When the current [deviceId] took effect; null = held since before tracking began. */
    var deviceIdSinceMs: Long? = null
    /** Ids this operator held before, oldest first. */
    var formerIds: Array<FormerSelfId>? = null
}

/** Which of the operator's ids a [FormerSelfId] was. Persisted by name (Gson), never by ordinal. */
enum class SelfIdKind { APP_ID, DEVICE_ID }

/** An id the operator held over [fromMs] (null = since forever) until [toMs], exclusive. */
data class FormerSelfId(
    val id: String?,
    val kind: SelfIdKind?,
    val fromMs: Long?,
    val toMs: Long,
)

/** Kept bounded: a handful of App ID or radio changes is the expected lifetime of a login. */
private const val MAX_FORMER_IDS = 32

/**
 * Carries the own-id history over from [previous] (the registration this one replaces) and
 * closes the period of each id it replaces at [nowMs]. Callers build a fresh user on every
 * write, so the history is taken here, never passed in. A re-registration with unchanged ids
 * changes nothing; after logout ([previous] null) there is no history.
 */
internal fun RegisterUser.takeHistoryFrom(previous: RegisterUser?, nowMs: Long) {
    if (previous == null) return
    val former = previous.formerIds.orEmpty().toMutableList()

    // The new "since" for one kind of id. A first id of its kind is held since forever, as
    // every id was before this history existed; one that replaces an earlier id starts now, so
    // messages a contact sent under it before the operator took it are not the operator's.
    fun step(kind: SelfIdKind, before: String?, since: Long?, after: String?): Long? {
        val old = before?.takeIf { it.isNotBlank() }
        val new = after?.takeIf { it.isNotBlank() }
        if (old == new) return since
        if (old != null) {
            // Clamped: a clock set back must not leave a period that ends before it starts.
            former += FormerSelfId(old, kind, since, maxOf(nowMs, since ?: nowMs))
        }
        return when {
            new == null -> null
            old != null || former.any { it.kind == kind } -> nowMs
            else -> null
        }
    }

    appIdSinceMs = step(SelfIdKind.APP_ID, previous.appId, previous.appIdSinceMs, appId)
    deviceIdSinceMs = step(SelfIdKind.DEVICE_ID, previous.deviceId, previous.deviceIdSinceMs, deviceId)
    formerIds = former.takeLast(MAX_FORMER_IDS).takeIf { it.isNotEmpty() }?.toTypedArray()
}


fun RegisterUser.toJson() : String {
    return Gson().toJson(this)
}
