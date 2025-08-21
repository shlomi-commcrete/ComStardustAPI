package com.commcrete.bittell.util.demo

import androidx.navigation.NavController
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.contacts.ContactsRepository
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.OpenStardustControlByte
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.FolderReader
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import timber.log.Timber

object DemoDataUtil {

    private val file = FileUtils.readFile(context = DataManager.context,
        fileType = ".json", fileName = "offlineDemo", folderName = "config")

    private val newDemoFile = FileUtils.readFile(context = DataManager.context,
        fileType = ".json", fileName = "newDemo", folderName = "config")

    fun getNewOfflineDemoData () : List<String> {
        val optionsList = mutableListOf<String>()
        optionsList.add("Excel")
        val json = Gson().fromJson(newDemoFile, JsonObject::class.java)
        val demos = json.getAsJsonObject("demos")
        for (key in demos.keySet()) {
            optionsList.add(key)
        }
        return optionsList
    }

    fun loadOfflineData(userId : String, key : String) {
        getOfflineDemoData(userId, key)
    }

    fun loadOfflineData(userId : String, demoList : List<FolderReader.ExcelUser>) {
        getOfflineDemoData(userId, demoList)
    }

    private fun getOfflineDemoData(userId: String, demoList : List<FolderReader.ExcelUser>){
        val demoUser = DemoUsers()
        demoUser.initUserList(demoList, userId)
        if(getLocalUser(demoUser, userId)){
            setupDatabases(demoUser)
        }else {
            Timber.tag("DemoDataUtil").d("User Not Found")
        }
    }

    private fun getOfflineDemoData(userId: String, key : String) {
        if(key == "Excel") {
            return
        }
        val json = Gson().fromJson(newDemoFile, JsonObject::class.java)
        val demos = json.getAsJsonObject("demos")
        val chat = demos.getAsJsonObject(key)
        val demoUser = DemoUsers()
        demoUser.initUserList(chat, userId)
        if(getLocalUser(demoUser, userId)){
            setupDatabases(demoUser)
        }else {
            Timber.tag("DemoDataUtil").d("User Not Found")
        }
    }
    fun loadOfflineData(userId : String) {
        getOfflineDemoData(userId)
    }

    private fun getOfflineDemoData(userId: String) {
        val json = Gson().fromJson(file, JsonObject::class.java)
        val demoUser = DemoUsers()
        demoUser.initUserList(json, userId)
        if(getLocalUser(demoUser, userId)){
            setupDatabases(demoUser)
        }else {
            Timber.tag("DemoDataUtil").d("User Not Found")
        }
    }

    private fun setupDatabases (demoUsers: DemoUsers) {
        Scopes.getDefaultCoroutine().launch {
            ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao()).addChats(demoUsers.mutableUserList)
            MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao()).addMessages(demoUsers.mutableMessagesList)
            ContactsRepository(ContactsDatabase.getDatabase(DataManager.context).contactsDao()).addAllContacts(demoUsers.mutableContactsList)
            onFinishLoadData()
        }
    }

    private fun setLocalUser(chatItem: ChatItem) {
        val newUser = RegisterUser(displayName = chatItem.name, password = "", licenseType = "", phone = "",
            location = arrayOf(0.0,0.0,0.0), appId = chatItem.user?.appId?.get(0), bittelId =
            chatItem.user?.bittelId?.get(0)
        )
        SharedPreferencesUtil.setAppUser(DataManager.context, newUser)
    }

    private fun getLocalUser(demoUser: DemoUsers, userId: String) : Boolean {
        var isFoundUser = false
        val iterator = demoUser.mutableUserList.iterator()
        while (iterator.hasNext()) {
            val user = iterator.next()
            if(userId == user.chat_id){
                setLocalUser(user)
                iterator.remove()
                isFoundUser = true
            }
        }
        return isFoundUser
    }

    private fun onFinishLoadData() {
        Scopes.getMainCoroutine().launch {
            val mPackage = StardustPackageUtils.getStardustPackage(
                source = "1" , destenation = "1", stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADDRESS)
            mPackage.openControlByte.stardustCryptType = OpenStardustControlByte.StardustCryptType.DECRYPTED
            DataManager.getClientConnection(DataManager.context).addMessageToQueue(mPackage)
        }
    }
}