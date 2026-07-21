@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.contact

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Write model for inserting/updating contacts.
 *
 * Each subtype contains only relevant identity/device data for that contact kind.
 */
@Serializable
sealed class FullContactData {
    abstract val contact: ContactEntity

    @Serializable
    @SerialName("User")
    data class User(
        override val contact: ContactEntity,
        val userId: String,
        val devices: List<DeviceEntity> = emptyList(),
    ) : FullContactData()

    @Serializable
    @SerialName("Group")
    data class Group(
        override val contact: ContactEntity,
        val groupId: String,
    ) : FullContactData()

    @Serializable
    @SerialName("Device")
    data class Device(
        override val contact: ContactEntity,
        val deviceId: String,
        val deviceData: DeviceEntity,
    ) : FullContactData()

    fun getMainCommunicationId(): String = when(this) {
            is User -> userId
            is Group -> groupId
            is Device -> deviceId
    }

    companion object {
        fun createUserContact(
            name: String,
            image: String?,
            userId: String?,
            deviceId: String? = null,
            model: String? = null,
            serial: String? = null,
        ): FullContactData? {
            val normalizedUserId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val normalizedDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
            return User(
                contact = ContactEntity(name = name, image = image, type = ContactType.USER),
                userId = normalizedUserId,
                devices = normalizedDeviceId?.let {
                    listOf(DeviceEntity(id = it, model = model, serial = serial))
                } ?: emptyList(),
            )
        }

        fun createGroupContact(
            name: String,
            image: String?,
            groupId: String?): FullContactData? {
            val normalizedGroupId = groupId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Group(
                contact = ContactEntity(name = name, image = image, type = ContactType.GROUP),
                groupId = normalizedGroupId,
            )
        }

        fun createDeviceContact(
            name: String,
            image: String?,
            deviceId: String,
            model: String? = null,
            serial: String? = null,
        ): FullContactData? {
            val normalizedDeviceId = deviceId.trim().takeIf { it.isNotEmpty() } ?: return null
            return Device(
                contact = ContactEntity(name = name, image = image, type = ContactType.DEVICE),
                deviceId = normalizedDeviceId,
                deviceData = DeviceEntity(
                    id = normalizedDeviceId,
                    model = model,
                    serial = serial,
                ),
            )
        }
    }
}
