package com.commcrete.stardust.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.bittel_package.BittelUsbManager
import com.commcrete.stardust.StardustAPI
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.BleScanner
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.location.PollingUtils
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustPackageHandler
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import timber.log.Timber

object DataManager : StardustAPI, PttInterface{

    private var clientConnection : ClientConnection?  = null
    private var bittelusbManager : BittelUsbManager?  = null
    private var bittelPackageHandler : StardustPackageHandler? = null
    private var pollingUtils : PollingUtils? = null
    lateinit var context : Context
    lateinit var fileLocation : String
    private var bleScanner : BleScanner? = null
    private var source : String? = null
    private var destination : String? = null

    private var hasTimber = false

    fun requireContext (context: Context){
        this.context = context
        if(!hasTimber) {
            Timber.plant(Timber.DebugTree())
        }
    }

    internal fun getClientConnection (context: Context) : ClientConnection {
        requireContext(context)
        if(clientConnection == null) {
            clientConnection = ClientConnection(context = context)
        }

        getStardustPackageHandler(context)

        return clientConnection!!
    }

    internal fun getUsbManager (context: Context) : BittelUsbManager {
        requireContext(context)
        if(bittelusbManager == null) {
            bittelusbManager =
                BittelUsbManager
            bittelusbManager?.init(context)
        }


        return bittelusbManager!!
    }

    internal fun getStardustPackageHandler(context: Context): StardustPackageHandler {
        requireContext(context)
        if(bittelPackageHandler == null){
            bittelPackageHandler = StardustPackageHandler(context, clientConnection)
        }
        return bittelPackageHandler!!
    }

    internal fun getPollingUtils () : PollingUtils{
        if(pollingUtils == null){
            pollingUtils = PollingUtils(context)
        }
        return pollingUtils!!
    }

    override fun sendMessage(stardustAPIPackage: StardustAPIPackage, text: String) {

    }
    @SuppressLint("MissingPermission")
    override fun startPTT(stardustAPIPackage: StardustAPIPackage) {
        this.source = stardustAPIPackage.source
        RecorderUtils.init(this)
        RecorderUtils.onRecord(true, stardustAPIPackage.destination)
    }
    @SuppressLint("MissingPermission")
    override fun stopPTT(stardustAPIPackage: StardustAPIPackage) {
        RecorderUtils.onRecord(false, stardustAPIPackage.destination)
    }

    override fun sendLocation(stardustAPIPackage: StardustAPIPackage, location: Location) {
        val stardustPackage = StardustPackageUtils.getStardustPackage(source = stardustAPIPackage.destination, destenation = stardustAPIPackage.source , stardustOpCode = StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION)
        LocationUtils.sendLocation(stardustPackage, location, getClientConnection(context))
    }


    override fun init(context: Context, fileLocation : String) {
        requireContext(context)
        requireFileLocation(fileLocation)
    }

    override fun scanForDevice() : MutableLiveData<List<ScanResult>> {
        val bleScanner = getBleScanner(this.context)
        bleScanner.startScan()
        return bleScanner.getScanResultsLiveData()
    }

    override fun connectToDevice(device: BluetoothDevice) {
        getClientConnection(context).connectDevice(device)
        val bleScanner = getBleScanner(this.context)
        bleScanner.stopScan()
        this.bleScanner = null
    }

    override fun disconnectFromDevice() {
        getClientConnection(context).disconnectFromDevice()
    }

    override suspend fun readChats(): LiveData<List<ChatItem>> {
        val bittelUserList = GlobalScope.async {
            return@async getChatsRepo().readAllChats
        }
        return bittelUserList.await()
    }

    override fun getSource(): String {
        return this.source ?: ""
    }

    override fun getDestenation(): String? {
        return this.destination ?: ""
    }

    override fun sendDataToBle(bittelPackage: StardustPackage) {
        getClientConnection(context).addMessageToQueue(bittelPackage)
    }

    fun requireFileLocation (location : String) {
        this.fileLocation = location
    }

    fun getBleScanner (context: Context): BleScanner {
        requireContext(context)
        if(this.bleScanner == null) {
            bleScanner = BleScanner(context)
        }
        return bleScanner!!
    }

    fun getChatsRepo () : ChatsRepository {
        return ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
    }

    fun getMessagesRepo () : MessagesRepository {
        return MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao())
    }
}