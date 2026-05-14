package com.commcrete.stardust.util


import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.stardust.StardustInitConnectionHandler

object ContactsFileParserUtil {

    suspend fun saveContactsToDatabase(rawData: List<FolderReader.ExcelUser>) {
        val contacts = parseContactsForDb(rawData)
        DataManager.getAppRepo().insertContactsWithChats(contacts)
    }

    fun registerSelectedUser(selectedUser: FolderReader.ExcelUser): Boolean {

        if(selectedUser.id.isEmpty()) { return false }

        val newUser = RegisterUser(
            displayName = selectedUser.name,
            deviceId = selectedUser.deviceId,
            appId = selectedUser.id
        )
        SharedPreferencesUtil.setAppUser(newUser)

        return true

    }

    private fun parseContactsForDb(contacts: List<FolderReader.ExcelUser>): List<FullContactData> {
        val result = mutableListOf<FullContactData>()

        for (contact in contacts) {
            val appId = contact.id
            val deviceId = contact.deviceId
            val name = contact.name
            val image = contact.image

            val type = when(contact.type.lowercase()) {
                "app" -> ContactType.USER
                "group" -> ContactType.GROUP
                else -> ContactType.DEVICE
            }

            val fullContact = when (type) {
                ContactType.USER -> FullContactData.Companion.createUserContact(
                    name = name,
                    image = image,
                    userId = appId,
                    deviceId = deviceId,
                    model = contact.model,
                    serial = contact.serial,
                )
                ContactType.GROUP -> FullContactData.Companion.createGroupContact(
                    name = name,
                    image = image,
                    groupId = appId,
                )
                ContactType.DEVICE -> FullContactData.Companion.createDeviceContact(
                    name = name,
                    image = image,
                    deviceId = deviceId,
                    model = contact.model,
                    serial = contact.serial,
                )
            }

            fullContact?.let { result.add(it) }
        }
        return result
    }


    private fun onFinishLoadData() {
        if(!BleManager.isBluetoothEnabled() && !BleManager.isUSBConnected) { return }
        StardustInitConnectionHandler.start()
    }

}