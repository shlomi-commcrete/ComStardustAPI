package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactsDao {

    /** Flat projection row used for reconstructing USER/DEVICE FullContactData. */
    data class AppContactRow(
        @Embedded
        val contact: ContactEntity,
        @ColumnInfo(name = "user_id")
        val userId: String?,
        @ColumnInfo(name = "device_id")
        val deviceId: String?,
        @ColumnInfo(name = "device_model")
        val deviceModel: String?,
        @ColumnInfo(name = "device_serial")
        val deviceSerial: String?,
        @ColumnInfo(name = "device_slot")
        val deviceSlot: Int?,
    )

    /** Flat projection row used for reconstructing GROUP FullContactData. */
    data class GroupContactRow(
        @Embedded
        val contact: ContactEntity,
        @ColumnInfo(name = "group_id")
        val groupId: String,
    )

    /**
     * Lightweight (chatId, participantId, type) row used to assemble
     * [com.commcrete.stardust.room.new_db.chat.ChatWithParticipantsAsShortParticipantInfo]
     * in a single bulk query rather than per-participant lookups.
     *
     * USER contacts emit one row per linked deviceId AND one row for the userId.
     * DEVICE contacts emit one row with the deviceId.
     * GROUP contacts emit one row with the groupId.
     */
    data class ChatParticipantIdRow(
        @ColumnInfo(name = "chat_id") val chatId: String,
        @ColumnInfo(name = "participant_id") val participantId: String,
        @ColumnInfo(name = "kind") val kind: String, // "USER" | "DEVICE" | "GROUP"
    )

    /** (normalized id -> contactId) row used for repository contact-cache preload. */
    data class IdContactRow(
        @ColumnInfo(name = "norm_id") val normalizedId: String,
        @ColumnInfo(name = "contact_id") val contactId: Int,
    )

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun getContactEntity(id: Int): ContactEntity?

    @Query(
        """
        SELECT user_id FROM app_contact_user_ids
        UNION
        SELECT device_id FROM app_contact_devices
        UNION 
        SELECT group_id FROM app_contact_group_ids
        """
    )
    fun getAllContactIds(): List<String>

    @Query("SELECT user_id FROM app_contact_user_ids")
    fun getAllAppIds(): List<String>

    @Query("SELECT device_id FROM app_contact_devices")
    fun getAllDevicesIds(): List<String>

    @Query("SELECT group_id FROM app_contact_group_ids")
    fun getAllGroupIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContact(contact: ContactEntity): Long

    @Update
    suspend fun updateContact(contact: ContactEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserAppId(contactUserId: ContactUserIdEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupId(contactGroupId: ContactGroupIdEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContactDevice(contactDevice: ContactDeviceEntity)

    @Query("SELECT contact_id FROM app_contact_user_ids WHERE user_id = :userId LIMIT 1")
    suspend fun findContactIdByUserId(userId: String): Int?

    @Query("SELECT contact_id FROM app_contact_group_ids WHERE group_id = :groupId LIMIT 1")
    suspend fun findContactIdByGroupId(groupId: String): Int?

    @Query("SELECT contact_id FROM app_contact_devices WHERE device_id = :deviceId LIMIT 1")
    suspend fun findContactIdByDeviceId(deviceId: String): Int?

    /** Single-shot lookup: returns contactId if [id] matches a userId or deviceId. */
    @Query(
        """
        SELECT contact_id FROM app_contact_user_ids WHERE user_id   = :id
        UNION
        SELECT contact_id FROM app_contact_devices  WHERE device_id = :id
        LIMIT 1
        """
    )
    suspend fun findContactIdByUserOrDeviceId(id: String): Int?

    /**
     * Returns the contactId that owns [id] across user / group / device identity
     * tables, or null when no contact owns it. Matches the semantics of
     * [FullContactData.getMainCommunicationId].
     */
    @Query(
        """
        SELECT contact_id FROM app_contact_user_ids  WHERE user_id   = :id
        UNION
        SELECT contact_id FROM app_contact_group_ids WHERE group_id  = :id
        UNION
        SELECT contact_id FROM app_contact_devices   WHERE device_id = :id
        LIMIT 1
        """
    )
    suspend fun findContactIdByMainCommunicationId(id: String): Int?

    /**
     * Returns the subset of [ids] that match a userId, groupId, or deviceId in any
     * identity table — i.e. every id that already maps to a known contact.
     * Single bulk query; callers can compute the missing set by subtracting the
     * result from the input.
     */
    @Query(
        """
        SELECT user_id  AS norm_id FROM app_contact_user_ids  WHERE user_id  IN (:ids)
        UNION
        SELECT group_id AS norm_id FROM app_contact_group_ids WHERE group_id IN (:ids)
        UNION
        SELECT device_id AS norm_id FROM app_contact_devices  WHERE device_id IN (:ids)
        """
    )
    suspend fun findExistingMainCommunicationIds(ids: List<String>): List<String>

    /**
     * All (normalizedId -> contactId) pairs across user_ids, device_ids, group_ids.
     * Used by repository to build the contact-id cache in one round-trip.
     */
    @Query(
        """
        SELECT user_id   AS norm_id, contact_id FROM app_contact_user_ids
        UNION ALL
        SELECT device_id AS norm_id, contact_id FROM app_contact_devices
        UNION ALL
        SELECT group_id  AS norm_id, contact_id FROM app_contact_group_ids
        """
    )
    suspend fun getAllIdToContactRows(): List<IdContactRow>

    /**
     * Returns every (chatId, participantId, kind) tuple in one query.
     * For USER contacts emits BOTH the userId row and one row per linked deviceId.
     * For DEVICE/GROUP contacts emits a single row.
     */
    @Query(
        """
        SELECT cp.chat_id AS chat_id, u.user_id AS participant_id, 'USER' AS kind
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_user_ids u ON u.contact_id = c.id
        WHERE c.type = 'USER'
        UNION ALL
        SELECT cp.chat_id, cd.device_id, 'DEVICE'
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_devices cd ON cd.contact_id = c.id
        WHERE c.type IN ('USER','DEVICE')
        UNION ALL
        SELECT cp.chat_id, g.group_id, 'GROUP'
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_group_ids g ON g.contact_id = c.id
        WHERE c.type = 'GROUP'
        """
    )
    suspend fun getAllChatParticipantIdRows(): List<ChatParticipantIdRow>

    /** Reactive variant of [getAllChatParticipantIdRows]. */
    @Query(
        """
        SELECT cp.chat_id AS chat_id, u.user_id AS participant_id, 'USER' AS kind
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_user_ids u ON u.contact_id = c.id
        WHERE c.type = 'USER'
        UNION ALL
        SELECT cp.chat_id, cd.device_id, 'DEVICE'
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_devices cd ON cd.contact_id = c.id
        WHERE c.type IN ('USER','DEVICE')
        UNION ALL
        SELECT cp.chat_id, g.group_id, 'GROUP'
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_group_ids g ON g.contact_id = c.id
        WHERE c.type = 'GROUP'
        """
    )
    fun observeAllChatParticipantIdRows(): Flow<List<ChatParticipantIdRow>>

    /** Same as [getAllChatParticipantIdRows] but scoped to one chat. */
    @Query(
        """
        SELECT cp.chat_id AS chat_id, u.user_id AS participant_id, 'USER' AS kind
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_user_ids u ON u.contact_id = c.id
        WHERE c.type = 'USER' AND cp.chat_id = :chatId
        UNION ALL
        SELECT cp.chat_id, cd.device_id, 'DEVICE'
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_devices cd ON cd.contact_id = c.id
        WHERE c.type IN ('USER','DEVICE') AND cp.chat_id = :chatId
        UNION ALL
        SELECT cp.chat_id, g.group_id, 'GROUP'
        FROM chat_participants cp
        JOIN contacts c ON c.id = cp.contact_id
        JOIN app_contact_group_ids g ON g.contact_id = c.id
        WHERE c.type = 'GROUP' AND cp.chat_id = :chatId
        """
    )
    suspend fun getChatParticipantIdRows(chatId: String): List<ChatParticipantIdRow>

    /**
     * Returns the contact name for [id] by searching across all three identity
     * tables (user IDs, group IDs, device IDs) in a single query.
     * Returns null when no contact owns that ID.
     */
    @Query(
        """
        SELECT c.name
        FROM contacts c
        INNER JOIN (
            SELECT contact_id FROM app_contact_user_ids WHERE user_id = :id
            UNION
            SELECT contact_id FROM app_contact_group_ids WHERE group_id = :id
            UNION
            SELECT contact_id FROM app_contact_devices WHERE device_id = :id
        ) ids ON ids.contact_id = c.id
        LIMIT 1
        """
    )
    suspend fun findContactNameById(id: String): String?


    /**
     * Returns all USER and GROUP contacts. DEVICE-only contacts are excluded.
     */
    @Query(
        """
        SELECT DISTINCT c.*
        FROM contacts c
        INNER JOIN (
            SELECT contact_id FROM app_contact_user_ids
            UNION
            SELECT contact_id FROM app_contact_group_ids
        ) ids ON ids.contact_id = c.id
        """
    )
    suspend fun getUserAndGroupContacts(): List<ContactEntity>

    @Query(
        """
        SELECT DISTINCT c.*
        FROM contacts c
        INNER JOIN (
            SELECT contact_id FROM app_contact_user_ids WHERE user_id != :excludedUserId
            UNION
            SELECT contact_id FROM app_contact_group_ids
        ) ids ON ids.contact_id = c.id
        """
    )
    suspend fun getUserAndGroupContactsExceptUser(excludedUserId: String): List<ContactEntity>
    
    @Query(
        """
        SELECT c.name
        FROM contacts c
        JOIN app_contact_group_ids gid ON gid.contact_id = c.id
        WHERE gid.group_id = :id
        LIMIT 1
        """
    )
    suspend fun findGroupNameById(id: String): String?
    

    /** Returns IDs of all contacts that are not of type GROUP. */
    @Query("SELECT id FROM contacts WHERE type != 'GROUP'")
    suspend fun getAllMemberContactIds(): List<Int>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun getContactById(id: Int): ContactEntity?

    @Query(
        """
        SELECT
            c.*,
            u.user_id AS user_id
        FROM contacts c
        LEFT JOIN app_contact_user_ids u ON u.contact_id = c.id
        WHERE c.type = 'USER'
        ORDER BY c.name ASC
        """
    )
    suspend fun getAllAppContactRows(): List<AppContactRow>

    @Query(
        """
        SELECT
            c.*,
            u.user_id AS user_id
        FROM contacts c
        LEFT JOIN app_contact_user_ids u ON u.contact_id = c.id
        WHERE c.type = 'USER' AND (u.user_id IS NULL OR u.user_id != :excludedUserId)
        ORDER BY c.name ASC
        """
    )
    suspend fun getAllAppContactRowsExceptUser(excludedUserId: String): List<AppContactRow>

    /**
     * Returns USER and DEVICE contacts with their device links joined in.
     * Each USER contact may produce multiple rows (one per linked device); DEVICE
     * contacts produce one row per device link. Used for assembling
     * [FullContactData.User] (with attached devices) and [FullContactData.Device].
     */
    @Query(
        """
        SELECT
            c.*,
            u.user_id    AS user_id,
            d.id         AS device_id,
            d.model      AS device_model,
            d.serial     AS device_serial,
            cd.slot      AS device_slot
        FROM contacts c
        LEFT JOIN app_contact_user_ids u ON u.contact_id = c.id
        LEFT JOIN app_contact_devices  cd ON cd.contact_id = c.id
        LEFT JOIN devices              d  ON d.id         = cd.device_id
        WHERE c.type IN ('USER', 'DEVICE')
        ORDER BY c.name ASC, cd.slot ASC
        """
    )
    suspend fun getAllUserAndDeviceContactRows(): List<AppContactRow>

    /** Variant of [getAllUserAndDeviceContactRows] that excludes the registered user by user_id. */
    @Query(
        """
        SELECT
            c.*,
            u.user_id    AS user_id,
            d.id         AS device_id,
            d.model      AS device_model,
            d.serial     AS device_serial,
            cd.slot      AS device_slot
        FROM contacts c
        LEFT JOIN app_contact_user_ids u ON u.contact_id = c.id
        LEFT JOIN app_contact_devices  cd ON cd.contact_id = c.id
        LEFT JOIN devices              d  ON d.id         = cd.device_id
        WHERE c.type IN ('USER', 'DEVICE')
          AND c.id NOT IN (
              SELECT contact_id FROM app_contact_user_ids WHERE user_id = :excludedUserId
          )
        ORDER BY c.name ASC, cd.slot ASC
        """
    )
    suspend fun getAllUserAndDeviceContactRowsExceptUser(excludedUserId: String): List<AppContactRow>

    @Query(
        """
        SELECT
            c.*,
            g.group_id AS group_id
        FROM contacts c
        INNER JOIN app_contact_group_ids g ON g.contact_id = c.id
        WHERE c.type = 'GROUP'
        ORDER BY c.name ASC
        """
    )
    suspend fun getAllGroupContactRows(): List<GroupContactRow>

    /**
     * Live stream of group IDs (group_id) that are fully resolved:
     * GROUP contact -> participant row -> GROUP chat.
     */
    @Query(
        """
        SELECT DISTINCT gid.group_id
        FROM app_contact_group_ids gid
        JOIN chat_participants cp ON cp.contact_id = gid.contact_id
        JOIN chats ch ON ch.id = cp.chat_id
        WHERE ch.type = 'GROUP'
        ORDER BY gid.group_id ASC
        """
    )
    fun observeResolvedGroupIds(): Flow<List<String>>

    @Query(
        """
        SELECT DISTINCT gid.group_id
        FROM app_contact_group_ids gid
        JOIN chat_participants cp ON cp.contact_id = gid.contact_id
        JOIN chats ch ON ch.id = cp.chat_id
        WHERE ch.type = 'GROUP'
        ORDER BY gid.group_id ASC
        """
    )
    suspend fun getResolvedGroupIds(): List<String>

    @Query(
        """
        SELECT ch.id
        FROM app_contact_group_ids gid
        JOIN chat_participants cp ON cp.contact_id = gid.contact_id
        JOIN chats ch ON ch.id = cp.chat_id
        WHERE gid.group_id = :groupId
          AND ch.type = 'GROUP'
        LIMIT 1
        """
    )
    suspend fun findResolvedGroupChatIdByGroupId(groupId: String): String?

    /** Inserts/updates all contacts and returns each one paired with its resolved contact ID. */
    @Transaction
    suspend fun addContacts(contacts: List<FullContactData>): List<Pair<FullContactData, Int>> =
        contacts.map { it to addContact(it) }

    @Transaction
    suspend fun addContact(fullContactData: FullContactData): Int {
        val normalizedPrimaryId = resolveNormalizedPrimaryId(fullContactData) ?: return 0
        val normalizedDevices = resolveNormalizedDevices(fullContactData)

        val existingByPrimaryId = findExistingByPrimaryId(fullContactData, normalizedPrimaryId)
        val existingByDevice = findExistingByDevice(normalizedDevices)

        val contactId = resolveOrCreateContactId(
            fullContactData = fullContactData,
            existingByPrimaryId = existingByPrimaryId,
            existingByDevice = existingByDevice,
        )

        upsertPrimaryIdentity(
            fullContactData = fullContactData,
            normalizedPrimaryId = normalizedPrimaryId,
            contactId = contactId,
            normalizedDevices = normalizedDevices,
        )
        upsertDeviceLinks(normalizedDevices, contactId)

        return contactId
    }

    private fun resolveNormalizedPrimaryId(fullContactData: FullContactData): String? =
        when (fullContactData) {
            is FullContactData.User -> fullContactData.userId.normalizedIdOrNull()
            is FullContactData.Group -> fullContactData.groupId.normalizedIdOrNull()
            is FullContactData.Device -> fullContactData.deviceId.normalizedIdOrNull()
        }

    private fun resolveNormalizedDevices(fullContactData: FullContactData): List<DeviceEntity> {
        val sourceDevices: List<DeviceEntity> = when (fullContactData) {
            is FullContactData.User -> fullContactData.devices
            is FullContactData.Group -> emptyList()
            is FullContactData.Device -> listOf(fullContactData.deviceData)
        }

        return sourceDevices
            .mapNotNull { device ->
                device.id.normalizedIdOrNull()?.let { normalizedId ->
                    device.copy(id = normalizedId)
                }
            }
            .distinctBy { it.id }
    }

    private suspend fun findExistingByPrimaryId(
        fullContactData: FullContactData,
        normalizedPrimaryId: String,
    ): Int? = when (fullContactData) {
        is FullContactData.User -> findContactIdByUserId(normalizedPrimaryId)
            ?: findContactIdByDeviceId(normalizedPrimaryId)
        is FullContactData.Group -> findContactIdByGroupId(normalizedPrimaryId)
        is FullContactData.Device -> findContactIdByDeviceId(normalizedPrimaryId)
    }

    private suspend fun findExistingByDevice(normalizedDevices: List<DeviceEntity>): Int? =
        normalizedDevices.firstNotNullOfOrNull { findContactIdByDeviceId(it.id) }

    private suspend fun resolveOrCreateContactId(
        fullContactData: FullContactData,
        existingByPrimaryId: Int?,
        existingByDevice: Int?,
    ): Int {
        val contactId = existingByPrimaryId
            ?: existingByDevice
            ?: insertContact(fullContactData.contact.copy(id = 0)).toInt()

        if (existingByPrimaryId != null || existingByDevice != null) {
            updateContact(fullContactData.contact.copy(id = contactId))
        }

        return contactId
    }

    private suspend fun upsertPrimaryIdentity(
        fullContactData: FullContactData,
        normalizedPrimaryId: String,
        contactId: Int,
        normalizedDevices: List<DeviceEntity>,
    ) {
        when (fullContactData) {
            is FullContactData.User -> {
                upsertUserAppId(ContactUserIdEntity(userId = normalizedPrimaryId, contactId = contactId))
            }

            is FullContactData.Group -> {
                upsertGroupId(ContactGroupIdEntity(groupId = normalizedPrimaryId, contactId = contactId))
            }

            is FullContactData.Device -> {
                val hasPrimaryDevice = normalizedDevices.any { it.id == normalizedPrimaryId }
                if (!hasPrimaryDevice) {
                    upsertDevice(DeviceEntity(id = normalizedPrimaryId))
                    upsertContactDevice(ContactDeviceEntity(deviceId = normalizedPrimaryId, contactId = contactId))
                }
            }
        }
    }

    private suspend fun upsertDeviceLinks(normalizedDevices: List<DeviceEntity>, contactId: Int) {
        normalizedDevices.forEach { device ->
            upsertDevice(device)
            upsertContactDevice(ContactDeviceEntity(deviceId = device.id, contactId = contactId))
        }
    }

    private fun String?.normalizedIdOrNull(): String? =
        this
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
}