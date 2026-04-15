package com.commcrete.stardust.room.new_db.contact

/**
 * Write model for inserting/updating contacts.
 *
 * Each subtype contains only relevant identity/device data for that contact kind.
 */
sealed class FullContactData {
    abstract val contact: ContactEntity

    data class User(
        override val contact: ContactEntity,
        val userId: String,
        val devices: List<DeviceEntity> = emptyList(),
    ) : FullContactData()

    data class Group(
        override val contact: ContactEntity,
        val groupId: String,
    ) : FullContactData()

    data class Device(
        override val contact: ContactEntity,
        val deviceId: String,
        val deviceData: DeviceEntity,
    ) : FullContactData()

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
                    listOf(DeviceEntity(deviceId = it, model = model, serial = serial))
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
                    deviceId = normalizedDeviceId,
                    model = model,
                    serial = serial,
                ),
            )
        }
    }
}
