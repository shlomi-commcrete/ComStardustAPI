package com.commcrete.stardust.util

import android.content.Context
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.legacy_db.chats.ChatItem
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.model.getSrcDestMin4Bytes

object ContactsFileParserUtil {

    suspend fun saveContactsToDatabase(appContext: Context, rawData: List<FolderReader.ExcelUser>) {
        val contacts = parseContactsForDb(rawData)
        DataManager.getAppRepo(appContext).insertContactsWithChats(contacts)
    }

    fun parseContactsForDb(contacts: List<FolderReader.ExcelUser>): List<FullContactData> {
        val result = mutableListOf<FullContactData>()

        for (contact in contacts) {
            val appId = contact.id.trim()
            val deviceId = contact.deviceId.getSrcDestMin4Bytes().trim()
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

    private fun setLocalUser(chatItem: ChatItem) {
        val newUser = RegisterUser(
            displayName = chatItem.name, password = "", licenseType = "", phone = "",
            location = arrayOf(0.0, 0.0, 0.0), appId = chatItem.user?.appId?.get(0), bittelId =
                chatItem.user?.bittelId?.get(0)
        )
        SharedPreferencesUtil.setAppUser(DataManager.context, newUser)
    }


    private fun onFinishLoadData() {
        if(!BleManager.isBluetoothEnabled() && !BleManager.isUSBConnected) { return }
        StardustInitConnectionHandler.start()
    }

}