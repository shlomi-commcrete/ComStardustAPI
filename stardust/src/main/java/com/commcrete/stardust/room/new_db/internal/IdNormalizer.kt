package com.commcrete.stardust.room.new_db.internal

/**
 * Pure id-normalization helpers shared across the new_db repository layer.
 *
 * Every identifier stored in the unified database (userId / deviceId / groupId /
 * chatId / participantId) is persisted lower-cased and trimmed. Callers must run
 * raw input through these helpers before issuing DAO queries or comparing against
 * cached values, otherwise lookups will silently miss.
 *
 * - [normalizeId] is the strict variant — assumes the caller has already
 *   guaranteed the value is a non-empty identifier. Returns the normalized form.
 * - [normalizeIdOrNull] is the lenient variant — returns null for null / blank
 *   inputs, suitable for filtering optional fields and incoming IPC payloads.
 */
fun normalizeId(value: String): String = value.trim().lowercase()

fun normalizeIdOrNull(value: String?): String? =
    value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

