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


    @Query("SELECT * FROM contacts_table WHERE id = :id LIMIT 1")
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

    /**
     * Returns the contact name for [id] by searching across all three identity
     * tables (user IDs, group IDs, device IDs) in a single query.
     * Returns null when no contact owns that ID.
     */
    @Query(
        """
        SELECT c.name
        FROM contacts_table c
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
        FROM contacts_table c
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
        FROM contacts_table c
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
        FROM contacts_table c
        JOIN app_contact_group_ids gid ON gid.contact_id = c.id
        WHERE gid.group_id = :id
        LIMIT 1
        """
    )
    suspend fun findGroupNameById(id: String): String?
    

    /** Returns IDs of all contacts that are not of type GROUP. */
    @Query("SELECT id FROM contacts_table WHERE type != 'GROUP'")
    suspend fun getAllMemberContactIds(): List<Int>

    @Query("SELECT * FROM contacts_table WHERE id = :id LIMIT 1")
    suspend fun getContactById(id: Int): ContactEntity?

    @Query(
        """
        SELECT
            c.*,
            u.user_id AS user_id,
            cd.device_id AS device_id,
            d.model AS device_model,
            d.serial AS device_serial,
            cd.slot AS device_slot
        FROM contacts_table c
        LEFT JOIN app_contact_user_ids u ON u.contact_id = c.id
        LEFT JOIN app_contact_devices cd ON cd.contact_id = c.id
        LEFT JOIN devices d ON d.id = cd.device_id
        WHERE c.type = 'USER'
        ORDER BY c.id ASC, cd.slot ASC, cd.assigned_at ASC
        """
    )
    suspend fun getAllAppContactRows(): List<AppContactRow>

    @Query(
        """
        SELECT
            c.*,
            u.user_id AS user_id,
            cd.device_id AS device_id,
            d.model AS device_model,
            d.serial AS device_serial,
            cd.slot AS device_slot
        FROM contacts_table c
        LEFT JOIN app_contact_user_ids u ON u.contact_id = c.id
        LEFT JOIN app_contact_devices cd ON cd.contact_id = c.id
        LEFT JOIN devices d ON d.id = cd.device_id
        WHERE c.type = 'USER' AND (u.user_id IS NULL OR u.user_id != :excludedUserId)
        ORDER BY c.id ASC, cd.slot ASC, cd.assigned_at ASC
        """
    )
    suspend fun getAllAppContactRowsExceptUser(excludedUserId: String): List<AppContactRow>

    @Query(
        """
        SELECT
            c.*,
            g.group_id AS group_id
        FROM contacts_table c
        INNER JOIN app_contact_group_ids g ON g.contact_id = c.id
        WHERE c.type = 'GROUP'
        ORDER BY c.id ASC
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