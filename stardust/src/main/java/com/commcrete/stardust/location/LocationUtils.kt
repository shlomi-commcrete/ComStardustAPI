package com.commcrete.stardust.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.request_objects.LocationMessage
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustLocationPackage
import com.commcrete.stardust.util.CoordinatesUtil
import com.commcrete.stardust.util.LogUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.room.beetle_users.BittelUserDatabase
import com.commcrete.stardust.room.beetle_users.BittelUserRepository
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.contacts.ContactsRepository
import com.commcrete.stardust.room.logs.LOG_EVENT
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.PermissionTracking
import com.google.android.gms.location.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.EasyPermissions
import java.util.Date
import kotlin.random.Random


object LocationUtils  {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var context: Context
    private lateinit var fragment: Fragment

    private val bittelUserDoa = BittelUserDatabase.getDatabase(context).bittelUserDao()
    private val bittelUserRepository = BittelUserRepository(bittelUserDoa)

    val contactsDao = ContactsDatabase.getDatabase(context).contactsDao()
    private val contactsRepository = ContactsRepository(contactsDao)

    private var lastLocation : Location? = null

    val listOfUserLocations : Flow<List<ChatContact>> = flow{
        contactsRepository.readAllContacts().map {
            emit(it)
        }
    }

    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val accuracy = SharedPreferencesUtil.getLocationAccuracy(context)
            for (location in locationResult.locations) {
                if(location.accuracy <= accuracy.toFloat()) {
                    lastLocation = location
                }
            }
        }
    }

    private val mutableMapPackages : MutableMap<String, StardustPackage> = mutableMapOf()
    private val mutableMapScopes : MutableMap<String, Job> = mutableMapOf()

    fun init(context: Context, fragment: Fragment? = null){
        LocationUtils.context = context
        if (fragment != null) {
            LocationUtils.fragment = fragment
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    }

    fun hasLocationPermission(): Boolean {
        return PermissionTracking.hasLocationPermission(context)
    }

    fun hasLocationPermissionForeground(): Boolean {
        return PermissionTracking.hasLocationPermissionForeground(context)
    }

    private fun requestPermissions () : Boolean{
        if (PermissionTracking.hasLocationPermission(context)){
            return true
        }else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O){
            EasyPermissions.requestPermissions(
                fragment,
                "You will need to accept the permission in order to run the application",
                100,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            return true
        }else{
            return false
        }
    }


    fun saveBittelUserLocation(locationMessage: LocationMessage, isCreateNewUser : Boolean = true){
        Scopes.getDefaultCoroutine().launch {
            val chatContact = contactsRepository.getChatContactByBittelID(locationMessage.src)
            if(chatContact != null) {
                chatContact?.let {
                    val contact = it
                    contact.lastUpdateTS = Date().time
                    contact.lat = locationMessage.latitude.toDouble()
                    contact.lon = locationMessage.longitude.toDouble()
                    contact.isSOS = false
                    contactsRepository.addContact(contact)
                    Scopes.getMainCoroutine().launch {
                        Toast.makeText(context, "Location Received From : ${contact.displayName  }", Toast.LENGTH_LONG ).show()
                    }
                }
            } else if(isCreateNewUser){
                createNewContact(locationMessage)
                saveBittelUserLocation(locationMessage, false)
            }
        }
    }

    private fun getSenderName(senderID: String): String{
        val contactsRepository = ContactsRepository(ContactsDatabase.getDatabase(context).contactsDao())
        return contactsRepository.getUserNameByUserId(senderID)
    }

    fun saveBittelUserLocation(bittelPackage: StardustPackage, bittelLocationPackage: StardustLocationPackage, isCreateNewUser : Boolean = true,
                               isSOS : Boolean = false){
        Scopes.getDefaultCoroutine().launch {
            val chatContact = contactsRepository.getChatContactByBittelID(bittelPackage.getSourceAsString())
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            if(chatContact != null) {
                chatContact.let {
                    val contact = it
                    var whoSent = ""
                    var displayName = contact.displayName
                    if(bittelPackage.getSourceAsString() == "00000002"){
                        whoSent = bittelPackage.getDestAsString()
                        val sender = chatsRepo.getChatByBittelID(whoSent)
                        sender?.let {
                            displayName = it.name
                        }
                    }else {
                        whoSent = bittelPackage.getSourceAsString()
                    }
                    contact.lastUpdateTS = Date().time
                    contact.lat = bittelLocationPackage.latitude.toDouble()
                    contact.lon = bittelLocationPackage.longitude.toDouble()
                    contact.isSOS = false
                    contactsRepository.addContact(contact)
                    Scopes.getMainCoroutine().launch {
                        Toast.makeText(context, "Location Received From : ${contact.displayName  }", Toast.LENGTH_LONG ).show()
                    }
                    val text = "latitude : ${bittelLocationPackage.latitude}\n" +
                            "longitude : ${bittelLocationPackage.longitude}\naltitude : ${bittelLocationPackage.height}"
                    val message = MessageItem(senderID = whoSent, text = text, epochTimeMs =  Date().time , seen = SeenStatus.RECEIVED,
                        senderName = displayName, chatId = bittelPackage.getSourceAsString(), isLocation = true, isSOS = isSOS)
                    MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).addContact(message)
                    saveLocationReceivedLog(whoSent, bittelPackage , bittelLocationPackage)
                    val pollingUtils = DataManager.getPollingUtils()
                    if(pollingUtils.isRunning) {
                        pollingUtils.handleResponse(bittelPackage)
                    }
                }
            } else if(isCreateNewUser) {
                createNewContact(bittelPackage)
                saveBittelUserLocation(bittelPackage, bittelLocationPackage, false)
            }
        }
    }

    private fun saveLocationReceivedLog(
        whoSent: String,
        bittelPackage: StardustPackage,
        bittelLocationPackage: StardustLocationPackage
    ) {
        val location = Location("")
        location.altitude = bittelLocationPackage.height.toDouble()
        location.latitude = bittelLocationPackage.latitude.toDouble()
        location.longitude = bittelLocationPackage.longitude.toDouble()
        val logObject = LogUtils.getLogObject(src = whoSent, dst = bittelPackage.getDestAsString()
            , event = LOG_EVENT.LOCATION_RECEIVED.type ,location = location)
        LogUtils.saveLog(logObject, context)
    }

    suspend fun createNewContact(bittelPackage: StardustPackage){
        val contact = ChatContact(displayName = bittelPackage.getSourceAsString(), number = bittelPackage.getSourceAsString(), bittelId = bittelPackage.getSourceAsString())
        ContactsRepository(ContactsDatabase.getDatabase(context).contactsDao()).addContact(contact)
    }

    suspend fun createNewContact(locationMessage: LocationMessage){
        val contact = ChatContact(displayName = locationMessage.src, number = locationMessage.src, bittelId = locationMessage.src)
        ContactsRepository(ContactsDatabase.getDatabase(context).contactsDao()).addContact(contact)
    }

    fun updatedLocationPullParams () {
        cancelLocationUpdates()
        requestLocationUpdates()
    }

    private fun cancelLocationUpdates () {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    @SuppressLint("MissingPermission")
    fun requestLocationUpdates () {
        val inter = SharedPreferencesUtil.getLocationInterval(context)
        val prior = SharedPreferencesUtil.getLocationPriority(context)
        val locationRequest = LocationRequest.create().apply {
            interval = inter.toLong()
            fastestInterval = (interval/2)
            priority = prior
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
    }
    @SuppressLint("MissingPermission")
    fun requestLocation() : Location?{
        if(!hasLocationPermissionForeground()){
            return null
        }else {
            lastLocation?.let { return it }
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    lastLocation = location
                }.addOnFailureListener{ e ->
                    e.printStackTrace()
                }
        }
        return null
    }

    internal fun sendMyLocation(mPackage: StardustPackage, clientConnection: ClientConnection, isDemandAck : Boolean = false,
                       isHR : Boolean = true, opCode : StardustPackageUtils.StardustOpCode? = null){
        val location = requestLocation()
        if(location == null){
            sendMissingLocation(mPackage, clientConnection, isDemandAck,isHR,opCode)
        }else {
            sendLocation(mPackage, location, clientConnection, isDemandAck,isHR,opCode)
        }
    }

    fun getLocationForSOSMyLocation(): Array<Int> {
        val location = requestLocation()
        if(location == null){
            return CoordinatesUtil().packEmptyLocation()
        }else {
            return CoordinatesUtil().packLocation(location)
        }
    }

    private fun sendMissingLocation(mPackage: StardustPackage, clientConnection : ClientConnection, isDemandAck : Boolean = false,
                                    isHR : Boolean = true, opCode : StardustPackageUtils.StardustOpCode? = null) {
        // TODO: send Cant find location ToPreviousDevice
        // TODO: change xor check
        Scopes.getDefaultCoroutine().launch {
            val bittelPackageToReturn = StardustPackageUtils.getStardustPackage(
                source = mPackage.getDestAsString() , destenation = mPackage.getSourceAsString(), stardustOpCode =
                if(opCode == null) mPackage.stardustOpCode else opCode, data =  CoordinatesUtil().packEmptyLocation()
            )

            bittelPackageToReturn.stardustControlByte.stardustAcknowledgeType = if(isDemandAck) StardustControlByte.StardustAcknowledgeType.DEMAND_ACK else StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            bittelPackageToReturn.stardustControlByte.stardustDeliveryType = if (isHR) StardustControlByte.StardustDeliveryType.HR else StardustControlByte.StardustDeliveryType.LR
            clientConnection.sendMessage(bittelPackageToReturn)
        }
        Scopes.getMainCoroutine().launch {
            if(!isLocationEnabled() && opCode == null){
                Toast.makeText(context, "No Location services, please enable", Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun sendLocation(mPackage: StardustPackage, location: Location, clientConnection : ClientConnection, isDemandAck : Boolean = false,
                             isHR : Boolean = true, opCode : StardustPackageUtils.StardustOpCode? = null) {
        // TODO: change xor check

        Scopes.getDefaultCoroutine().launch {
            val bittelPackageToReturn = StardustPackageUtils.getStardustPackage(
                source = mPackage.getDestAsString() , destenation = mPackage.getSourceAsString(), stardustOpCode =
                if(opCode == null) StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION else opCode,
                data =
                CoordinatesUtil().packLocation(location)
            )
            val id = Random.nextLong(Long.MAX_VALUE)
            bittelPackageToReturn.stardustControlByte.stardustAcknowledgeType = if(isDemandAck) StardustControlByte.StardustAcknowledgeType.DEMAND_ACK else StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            bittelPackageToReturn.isDemandAck = isDemandAck
            bittelPackageToReturn.idNumber = id
            bittelPackageToReturn.stardustControlByte.stardustDeliveryType = if (isHR) StardustControlByte.StardustDeliveryType.HR else StardustControlByte.StardustDeliveryType.LR

            clientConnection.sendMessage(bittelPackageToReturn)

            val text = "latitude : ${location.latitude.getAfterDot(4)}\n" +
                    "longitude : ${location.longitude.getAfterDot(6)}\naltitude : ${location.altitude.getAfterDot(0)}"
            saveLocationSent(sender = mPackage.getDestAsString(), chatId = mPackage.getSourceAsString() , locationText = text, senderName = "" , idNumber = id)
            Scopes.getMainCoroutine().launch {
                val logObject = LogUtils.getLogObject(src = mPackage.getDestAsString(), dst = mPackage.getSourceAsString()
                    , event = LOG_EVENT.LOCATION_SENT.type ,location = location)
                LogUtils.saveLog(logObject, context)
            }
        }
    }



    fun removePulling(dest : String){
        if(mutableMapPackages.containsKey(dest)){
            mutableMapPackages.remove(dest)
        }
        if(mutableMapScopes.containsKey(dest)){
            mutableMapScopes.get(dest)?.cancel(null)
            mutableMapScopes.remove(dest)
        }
    }

    internal fun addPulling(clientConnection: ClientConnection, dest : String, bittelPackage: StardustPackage) {
        removePulling(dest)
        mutableMapPackages.put(dest, bittelPackage)
        mutableMapScopes.put(dest, startPulling(dest, clientConnection))


    }

    private fun startPulling(dest: String, clientConnection: ClientConnection) : Job{
        val runnable = Scopes.getDefaultCoroutine().launch {
            if(mutableMapPackages.containsKey(dest)){
                val mPackage = mutableMapPackages.get(dest)
                mPackage?.let {
                    val delay = (it.pullTimer*1000).toLong()
                    while (delay.toInt() != 0) {
                        requestLocation(it,clientConnection)
                        delay(delay)
                    }
                }
            }
        }
        return runnable
    }

    private fun requestLocation(bittelPackage: StardustPackage, clientConnection : ClientConnection) {
        clientConnection.sendMessage(bittelPackage)
    }

    suspend fun saveLocationSent (sender : String, locationText : String, senderName : String, chatId : String, isDemandAck: Boolean = false, idNumber : Long = 0) {
        val message = MessageItem(senderID = sender, text = locationText, epochTimeMs =  Date().time ,
            senderName = senderName, chatId = chatId, isLocation = true, seen = if(chatId != "00000002") SeenStatus.SENT else SeenStatus.SEEN)
        message.isAck = isDemandAck
        message.idNumber = idNumber
        message.senderName = getSenderName(message.senderID)
        MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).addContact(message)
    }

    fun isLocationEnabled() : Boolean{
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?

// Check if the GPS and Network providers are enabled

// Check if the GPS and Network providers are enabled
        val isGpsEnabled = locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        return !(!isGpsEnabled && !isNetworkEnabled)

    }

    fun Double.getAfterDot (numAfterDot : Int): String {
        return String.format("%.${numAfterDot}f", this)
    }

    fun getRandomLocation(): Pair<Double, Double> {
        val randomLatitude = Random.nextDouble(0.0, 90.0)
        val randomLongitude = Random.nextDouble(0.0, 180.0)
        return Pair(randomLatitude, randomLongitude)
    }
}


