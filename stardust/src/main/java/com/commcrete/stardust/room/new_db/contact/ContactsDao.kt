package com.commcrete.stardust.room.new_db.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactsDao {


    @Query("SELECT name FROM contacts_table WHERE id = :id LIMIT 1")
    suspend fun getChatName(id: String) : String?

    @Query("UPDATE contacts_table SET name=:name WHERE id = :id")
    suspend fun updateChatName(id: String, name : String)

    /**
     * Get full contact data by ID, including all related user IDs and devices.
     * Joins with app_contact_user_ids and app_contact_devices.
     */
    @Transaction
    @Query("SELECT * FROM contacts_table WHERE id = :id LIMIT 1")
    suspend fun getFullChatData(id: Int): FullContactData?

    @Query(
        """
        SELECT user_id FROM app_contact_user_ids
        UNION
        SELECT device_id FROM app_contact_devices
        """
    )
    fun getAllContactIds(): List<String>

    @Query("SELECT user_id FROM app_contact_user_ids")
    fun getAllAppIds(): List<String>

    @Query("SELECT device_id FROM app_contact_devices")
    fun getAllDevicesIds(): List<String>

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
    suspend fun findResolvedGroupChatIdByGroupId(groupId: String): Int?

    @Transaction
    suspend fun addContacts(contacts: List<FullContactData>) {
        contacts.forEach { addContact(it) }
    }

    /** Inserts/updates all contacts and returns each one paired with its resolved contact ID. */
    @Transaction
    suspend fun addContactsAndGetIds(contacts: List<FullContactData>): List<Pair<FullContactData, Int>> =
        contacts.map { it to addContactAndGetId(it) }

    /** Same as [addContact] but returns the resolved contact ID. */
    @Transaction
    suspend fun addContactAndGetId(fullContactData: FullContactData): Int {
        addContact(fullContactData)
        val normalized = fullContactData.userId?.userId?.normalizedIdOrNull()
            ?: fullContactData.groupId?.groupId?.normalizedIdOrNull()
            ?: fullContactData.devices
                .mapNotNull { it.contactDevice.deviceId.normalizedIdOrNull() }
                .firstOrNull()
        return normalized?.let {
            findContactIdByUserId(it) 
                ?: findContactIdByGroupId(it)
                ?: findContactIdByDeviceId(it)
        } ?: 0
    }

    @Transaction
    suspend fun addContact(fullContactData: FullContactData) {
        val normalizedUserId = fullContactData.userId?.userId?.normalizedIdOrNull()
        val normalizedGroupId = fullContactData.groupId?.groupId?.normalizedIdOrNull()

        val normalizedDeviceRows = fullContactData.devices
            .mapNotNull { row ->
                row.contactDevice.deviceId.normalizedIdOrNull()?.let { normalizedId ->
                    row.copy(
                        contactDevice = row.contactDevice.copy(deviceId = normalizedId),
                        device = row.device?.copy(deviceId = normalizedId),
                    )
                }
            }
            .distinctBy { it.contactDevice.deviceId }

        val existingByUser = normalizedUserId?.let { findContactIdByUserId(it) }
        val existingByGroup = normalizedGroupId?.let { findContactIdByGroupId(it) }
        val existingByDevice = normalizedDeviceRows
            .firstNotNullOfOrNull { findContactIdByDeviceId(it.contactDevice.deviceId) }

        val contactId = existingByUser
            ?: existingByGroup
            ?: existingByDevice
            ?: insertContact(fullContactData.contact.copy(id = 0)).toInt()

        if (existingByUser != null || existingByGroup != null || existingByDevice != null) {
            updateContact(fullContactData.contact.copy(id = contactId))
        }

        normalizedUserId?.let { userId ->
            upsertUserAppId(ContactUserIdEntity(userId = userId, contactId = contactId))
        }

        normalizedGroupId?.let { groupId ->
            upsertGroupId(ContactGroupIdEntity(groupId = groupId, contactId = contactId))
        }

        normalizedDeviceRows.forEach { deviceRow ->
            val deviceId = deviceRow.contactDevice.deviceId
            upsertDevice(deviceRow.device ?: DeviceEntity(deviceId = deviceId))
            upsertContactDevice(deviceRow.contactDevice.copy(contactId = contactId))
        }
    }

    private fun String?.normalizedIdOrNull(): String? =
        this
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
}