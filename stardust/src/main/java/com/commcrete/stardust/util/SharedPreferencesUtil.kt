package com.commcrete.stardust.util

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.preference.PreferenceManager
import com.commcrete.stardust.R
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.request_objects.model.license.License
import com.commcrete.stardust.request_objects.toJson
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.util.audio.RecorderUtils
import com.google.android.gms.location.LocationRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SharedPreferencesUtil {
    private const val PACKAGE_NAME = "com.commcrete.bittell"
    private const val KEY_USER_ID = "user_info"
    private const val KEY_USER_OBJ = "user_obj"
    private const val KEY_BEETLE_DEVICE = "bittel_device"
    private const val KEY_BEETLE_DEVICE_NAME = "bittel_device_name"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ESP_PORT = "esp_port"
    private const val KEY_FIREBASE_TOKEN = "firebase_token"
    private const val KEY_LICENSES = "licenses"
    private const val KEY_DEVELOPER = "is_developer"
    private const val KEY_APP_USER = "app_user"

    //Preferences
    private const val KEY_HANDLE_GAIN = "handle_gain"
    private const val KEY_ENABLE_AUTO_GAIN_CONTROL = "enable_auto_gain_control"
    private const val KEY_ENABLE_NOISE_SUPPRESSOR = "enable_noise_suppressor"
    private const val KEY_ENABLE_ACOUSTIC_ECHO_CONTROL = "enable_acoustic_echo_control"
    private const val KEY_RECORDING_TYPE = "recording_type"
    private const val KEY_BITTEL_BIT_SERVER = "enable_bittel_server"
    private const val KEY_ENABLE_PTT_SOUND = "enable_ptt_sound"
    private const val KEY_SELECT_CONNECTIVITY_OPTIONS = "select_connectivity_options"
    private const val KEY_BITTEL_ACK = "enable_bittel_ack"
    private const val KEY_PTT_TIMEOUT = "ptt_timeout"

    //Record type Values
    private const val KEY_RECORDING_TYPE_DEFAULT = "Default"
    private const val KEY_RECORDING_TYPE_MIC = "Mic"
    private const val KEY_RECORDING_TYPE_VOICE_COMMUNICATION = "Voice Communication"
    private const val KEY_RECORDING_TYPE_VOICE_CALL = "Voice Call"
    private const val KEY_RECORDING_TYPE_VOICE_RECOGNITION = "Voice Recognition"

    //Location type Values
    private const val KEY_LOCATION_PRIORITY = "select_location_priority"
    private const val KEY_LOCATION_ACCURACY = "location_accuracy"
    private const val KEY_LOCATION_INTERVAL = "location_interval"
    private const val KEY_LOCATION_MANUAL = "location_manual"

    private const val KEY_IS_CONFIG_SAVED = "configSaved"

    //SOS Contacts
    private const val KEY_SOS_SELECTED_1 = "sos_selected_1"
    private const val KEY_SOS_SELECTED_2 = "sos_selected_2"

    private const val KEY_EQ_BAND = "eq_band_"

    private const val KEY_ADMIN_MODE = "admin_mode_type"
    private const val KEY_ADMIN_LOCAL_MODE = "admin_mode_type_name"

    private const val KEY_LAST_USER = "last_user"
    private const val KEY_ALERT_DEST = "alert_dest"

    //Output Values
    private const val KEY_DEFAULT_AUDIO_OUTPUT = "Builtin-Speakers"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_WIRED_HEADPHONES = "Wired-Headphones"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_USB_HEADSET = "USB-Headset"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_EARPIECE = "Earpiece"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_BLUETOOTH = "Bluetooth-Device"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_HDMI = "HDMI"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_BLUETOOTH_A2DP = "Bluetooth-A2DP"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_REMOTE_SUBMIX = "Remote-Submix"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_UNKNOWN = "Default"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_WIRED_HEADSET = "Wired-Headset"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_AUX_LINE = "Aux"
    private const val KEY_DEFAULT_AUDIO_OUTPUT_TYPE_TEL = "Telephony"

    //Input Values
    private const val KEY_DEFAULT_AUDIO_INPUT = "Builtin-Mic"
    private const val KEY_DEFAULT_AUDIO_INPUT_DEFAULT = "Default"
    private const val KEY_DEFAULT_AUDIO_INPUT_WIRED_HEADPHONES = "Wired-Headphones"
    private const val KEY_DEFAULT_AUDIO_INPUT_USB_HEADSET = "USB-Headset"
    private const val KEY_DEFAULT_AUDIO_INPUT_USB_DEVICE = "USB-Device"
    private const val KEY_DEFAULT_AUDIO_INPUT_EARPIECE = "Earpiece"
    private const val KEY_DEFAULT_AUDIO_INPUT_BLUETOOTH = "Bluetooth-Device"

    private const val KEY_OUTPUT_DEFAULT = "output_default"
    private const val KEY_INPUT_DEFAULT = "input_default"
    private const val KEY_INPUT_CODEC = "codec_type"

    //Carriers
    private const val KEY_LAST_CARRIERS = "last_carriers"
    private const val KEY_LAST_CARRIERS1 = "last_carriers1"
    private const val KEY_LAST_CARRIERS2 = "last_carriers2"
    private const val KEY_LAST_CARRIERS3 = "last_carriers3"
    private const val KEY_LAST_PRESETS = "last_presets"
    private const val KEY_SOS_DEFAULT = "sos_default"
    private const val KEY_TEXT_DEFAULT = "text_default"
    private const val KEY_LOCATION_DEFAULT = "location_default"
    private const val KEY_PTT_DEFAULT = "ptt_default"
    private const val KEY_BFT_DEFAULT = "bft_default"
    private const val KEY_FILE_DEFAULT = "file_default"
    private const val KEY_IMAGE_DEFAULT = "image_default"

    //Location
    private const val KEY_LOCATION_FORMAT = "location_format"
    private const val KEY_KEY_NAME = "key_name_crypto"
    private const val KEY_ERASE = "key_is_erased"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PACKAGE_NAME, Context.MODE_PRIVATE)
    }

    private fun getPrefsPlugin(context: Context): SharedPreferences {
        if(DataManager.pluginContext != null){
            return DataManager.pluginContext!!.getSharedPreferences(PACKAGE_NAME, Context.MODE_PRIVATE)
        }
        return context.getSharedPreferences(PACKAGE_NAME, Context.MODE_PRIVATE)

    }



    fun getUserID(context: Context): String? {
        return getPrefs(context).getString(KEY_USER_ID, null)
    }

    fun getPhoneNumber(context: Context): String? {
        return getPrefs(context).getString(KEY_PHONE_NUMBER, null)
    }

    fun savePhoneNumber(context: Context, phoneNumber: String) {
        getPrefs(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
    }

    fun getPassword(context: Context): String? {
        return getPrefs(context).getString(KEY_PASSWORD, null)
    }

    fun savePassword(context: Context, password: String) {
        getPrefs(context).edit().putString(KEY_PASSWORD, password).apply()
    }

    fun removePassword (context: Context) : Boolean {
        getPrefs(context).edit().remove(KEY_PASSWORD).apply()
        return true
    }

    fun removePhone (context: Context) : Boolean {
        getPrefs(context).edit().remove(KEY_PHONE_NUMBER).apply()
        return true
    }

    fun removeUserID(context: Context) {
        getPrefs(context).edit().remove(KEY_USER_ID).apply()
    }

    fun setUserID(context: Context , userId : String){
        getPrefs(context).edit().putString(KEY_USER_ID, userId).apply()
    }

    fun setBittelDevice(context: Context , bittelDevice : String){
        getPrefs(context).edit().putString(KEY_BEETLE_DEVICE, bittelDevice).apply()
    }

    fun removeBittelDevice(context: Context) : Boolean {
        getPrefs(context).edit().remove(KEY_BEETLE_DEVICE).apply()
        return true
    }

    fun setBittelDeviceName(context: Context , bittelDeviceName : String){
        getPrefs(context).edit().putString(KEY_BEETLE_DEVICE_NAME, bittelDeviceName).apply()
    }

    fun getBittelDeviceName(context: Context) : String?{
        return getPrefs(context).getString(KEY_BEETLE_DEVICE_NAME, "")
    }

    fun removeBittelDeviceName(context: Context) : Boolean {
        getPrefs(context).edit().remove(KEY_BEETLE_DEVICE_NAME).apply()
        return true
    }

    fun setLicenses(context: Context , licenses : String){
        getPrefs(context).edit().putString(KEY_LICENSES, licenses).apply()
    }

    fun getLicenses(context: Context) : License?{
        val licensesString = getPrefs(context).getString(KEY_LICENSES, "")
        return Gson().fromJson(licensesString, License::class.java)
    }

    fun getBittelDevice(context: Context) : String?{
        return getPrefs(context).getString(KEY_BEETLE_DEVICE, "")
    }

    fun setFirebaseToken(context: Context , token : String){
        getPrefs(context).edit().putString(KEY_FIREBASE_TOKEN, token).apply()
    }

    fun setAppUser (context: Context , appUser : RegisterUser) {
        getPrefs(context).edit().putString(KEY_APP_USER, appUser.toJson()).apply()
    }

    fun removeAppUser (context: Context) : Boolean{
        getPrefs(context).edit().remove(KEY_APP_USER).apply()
        return true
    }

    fun getAppUser (context: Context) : RegisterUser? {
        val userString = getPrefs(context).getString(KEY_APP_USER, "")
        if(!userString.isNullOrEmpty()) {
            return Gson().fromJson(userString, RegisterUser::class.java)
        }
        return null

    }

    fun getEspPortSelected(context: Context) : Boolean {
        return getPrefs(context).getBoolean(KEY_ESP_PORT, true)
    }

    fun setEspPort(context: Context, isSelected : Boolean){
        getPrefs(context).edit().putBoolean(KEY_ESP_PORT, isSelected).apply()
    }

    fun getFirebaseToken(context: Context) : String?{
        return getPrefs(context).getString(KEY_FIREBASE_TOKEN, "")
    }

    fun setUser(context: Context , user : String?){
        getPrefs(context).edit().putString(KEY_USER_OBJ, user).apply()
    }

    fun removeUser (context: Context) : Boolean {
        getPrefs(context).edit().remove(KEY_USER_OBJ).apply()
        return true
    }

    fun getUser(context: Context) : User?{
        val userString = getPrefs(context).getString(KEY_USER_OBJ, "")
        if(!userString.isNullOrEmpty()){
            var user = Gson().fromJson(userString, User::class.java)
            return user
        }
        return null
    }

    fun setDeveloperMode(context: Context , isDeveloper : Boolean = false) {
        getPrefs(context).edit().putBoolean(KEY_DEVELOPER, isDeveloper).apply()
    }

    fun isDeveloperMode (context: Context) : Boolean{
        return getPrefs(context).getBoolean(KEY_DEVELOPER, false)
    }

    private fun getPreferencesBoolean (context: Context, key :String) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getBoolean(key, false)
    }

    private fun getPreferencesInt (context: Context, key :String, default : Int = 0) : Int {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getInt(key, default)
    }

    private fun getPreferencesString (context: Context, key :String, default : String = "") : String? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getString(key, default)
    }

    fun getGain(context: Context) : Float{
        return getPreferencesInt(context, KEY_HANDLE_GAIN, 100).toFloat()
    }

    fun getAutoGainControl(context: Context) : Boolean{
        return getPreferencesBoolean(context, KEY_ENABLE_AUTO_GAIN_CONTROL)
    }

    fun getNoiseSuppressor(context: Context) : Boolean {
        return getPreferencesBoolean(context, KEY_ENABLE_NOISE_SUPPRESSOR)
    }

    fun getAcousticEchoControl(context: Context) : Boolean {
        return getPreferencesBoolean(context, KEY_ENABLE_ACOUSTIC_ECHO_CONTROL)
    }

    fun getAudioSource(context: Context) : Int {
        val audioSourceString = getPreferencesString(context, KEY_RECORDING_TYPE)
        if(audioSourceString != null) {
            audioSourceString.let {
                when (it) {
                    KEY_RECORDING_TYPE_MIC -> return MediaRecorder.AudioSource.MIC
//                    KEY_RECORDING_TYPE_VOICE_CALL -> return MediaRecorder.AudioSource.VOICE_CALL
                    KEY_RECORDING_TYPE_VOICE_COMMUNICATION -> return MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    KEY_RECORDING_TYPE_VOICE_RECOGNITION -> return MediaRecorder.AudioSource.VOICE_RECOGNITION
                    else -> { return MediaRecorder.AudioSource.MIC }
                }
            }
        }else {
            return MediaRecorder.AudioSource.MIC
        }
    }

    fun getEnablePttSound (context: Context) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getBoolean(KEY_ENABLE_PTT_SOUND, true)
    }

    fun getIsStardustServerBitEnabled(context: Context) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getBoolean(KEY_BITTEL_BIT_SERVER, false)
    }

    fun getConnectivityToggles (context: Context) : MutableSet<String>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val defaults = mutableSetOf( context.getString(R.string.bluetooth))
        return getPrefs(context).getStringSet(KEY_SELECT_CONNECTIVITY_OPTIONS, defaults)
    }

    fun getConfigSaved (context: Context) : Boolean {
        return getPreferencesBoolean(context, KEY_IS_CONFIG_SAVED)
    }

    fun setConfigSaved (context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putBoolean(KEY_IS_CONFIG_SAVED, true).apply()
    }

    fun isBittelAck (context: Context) : Boolean {
        return getPrefs(context).getBoolean(KEY_BITTEL_ACK, false)
    }

    fun getLocationInterval (context: Context) : Int {
        val value = getPreferencesString(context, KEY_LOCATION_INTERVAL, "4")
        return (value?.toInt() ?: 4) * 1000

    }

    fun setLocationInterval (context: Context, interval : String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_LOCATION_INTERVAL, interval).apply()
//        LocationUtils.updatedLocationPullParams()
    }

    fun getLocationPriority (context: Context) : Int{
        val priority = getPreferencesString(context, KEY_LOCATION_PRIORITY, "100")
        if(priority == "100") {
            return LocationRequest.PRIORITY_HIGH_ACCURACY
        }else if (priority == "102") {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        } else if (priority == "104") {
            LocationRequest.PRIORITY_LOW_POWER
        }
        return LocationRequest.PRIORITY_HIGH_ACCURACY

    }

    fun getLocationAccuracy (context: Context) : Int {
        val value = getPreferencesString(context, KEY_LOCATION_ACCURACY, "200")
        return value?.toInt() ?: 100
    }

    fun getSelectedSOSMain (context: Context) : String {
        return getPreferencesString(context, KEY_SOS_SELECTED_1, "") ?: ""
    }

    fun getSelectedSOSSub (context: Context) : String {
        return getPreferencesString(context, KEY_SOS_SELECTED_2, "") ?: ""
    }

    fun getEqBand (context: Context, bandNum : Int) : Int {
        val default = when(bandNum) {
            0 -> {-14}
            1 -> {-13}
            2 -> {0}
            3 -> {8}
            4 -> {-6}
            else -> {0}
        }
        return getPreferencesInt(context, KEY_EQ_BAND+bandNum, default) *100
    }
    fun getPTTTimeout (context: Context) : Int {
        val value = getPreferencesInt(context, KEY_PTT_TIMEOUT, 45)
        return value.times(1000)
    }

    fun setLastUser (context: Context, userId : String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_LAST_USER, userId).apply()
    }

    fun getLastUser (context: Context) : String {
        return getPreferencesString(context, KEY_LAST_USER, "") ?: ""
    }

    fun setAdminMode (context: Context,snifferMode: StardustConfigurationParser.SnifferMode ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putInt(KEY_ADMIN_MODE, snifferMode.type).apply()
    }

    fun getAdminMode (context: Context) : StardustConfigurationParser.SnifferMode {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val type =  getPrefs(context).getInt(KEY_ADMIN_MODE, 0)
        return StardustConfigurationParser.SnifferMode.values()[type]
    }

    fun getAdminLocalMode (context: Context) : AdminUtils.AdminLocal {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val type =  getPrefs(context).getString(KEY_ADMIN_LOCAL_MODE, context.getString(R.string.regular))
        when (type) {
            context.getString(R.string.regular) -> { return AdminUtils.AdminLocal.Regular}
            context.getString(R.string.admin) -> { return AdminUtils.AdminLocal.Admin}
            context.getString(R.string.superUser) -> { return AdminUtils.AdminLocal.SuperUser}
        }
        return AdminUtils.AdminLocal.Regular
    }

    fun setOutputDevice(context: Context, outputDevice : String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_OUTPUT_DEFAULT , outputDevice).apply()
    }

    fun setInputDevice(context: Context, inputDevice : String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_INPUT_DEFAULT , inputDevice).apply()
    }

    fun getOutputDevice(context: Context) : Int {
        val audioSourceString = getPreferencesString(context, KEY_OUTPUT_DEFAULT)
        if(audioSourceString != null) {
            audioSourceString.let {
                when (it) {
                    KEY_DEFAULT_AUDIO_OUTPUT -> return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    KEY_DEFAULT_AUDIO_OUTPUT_WIRED_HEADPHONES -> return AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    KEY_DEFAULT_AUDIO_OUTPUT_USB_HEADSET -> return AudioDeviceInfo.TYPE_USB_HEADSET
                    KEY_DEFAULT_AUDIO_OUTPUT_EARPIECE -> return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    KEY_DEFAULT_AUDIO_OUTPUT_BLUETOOTH -> return AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_HDMI -> return AudioDeviceInfo.TYPE_HDMI
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_BLUETOOTH_A2DP -> return AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_REMOTE_SUBMIX -> return AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_UNKNOWN -> return AudioDeviceInfo.TYPE_UNKNOWN
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_WIRED_HEADSET -> return AudioDeviceInfo.TYPE_WIRED_HEADSET
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_AUX_LINE -> return AudioDeviceInfo.TYPE_AUX_LINE
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_TEL -> return AudioDeviceInfo.TYPE_TELEPHONY
                    else -> { return AudioDeviceInfo.TYPE_UNKNOWN }
                }
            }
        }else {
            return AudioDeviceInfo.TYPE_UNKNOWN
        }
    }

    fun getInputDevice(context: Context) : Int {
        val audioSourceString = getPreferencesString(context, KEY_INPUT_DEFAULT)
        if(audioSourceString != null) {
            audioSourceString.let {
                when (it) {
                    KEY_DEFAULT_AUDIO_INPUT -> return AudioDeviceInfo.TYPE_BUILTIN_MIC
                    KEY_DEFAULT_AUDIO_INPUT_DEFAULT -> return AudioDeviceInfo.TYPE_UNKNOWN
                    KEY_DEFAULT_AUDIO_INPUT_WIRED_HEADPHONES -> return AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    KEY_DEFAULT_AUDIO_INPUT_USB_HEADSET -> return AudioDeviceInfo.TYPE_USB_HEADSET
                    KEY_DEFAULT_AUDIO_INPUT_USB_DEVICE -> return AudioDeviceInfo.TYPE_USB_DEVICE
                    KEY_DEFAULT_AUDIO_INPUT_EARPIECE -> return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    KEY_DEFAULT_AUDIO_INPUT_BLUETOOTH -> return AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    else -> { return AudioDeviceInfo.TYPE_UNKNOWN }
                }
            }
        }else {
            return AudioDeviceInfo.TYPE_UNKNOWN
        }
    }

    fun setPresets(context: Context, carriers: List<StardustConfigurationParser.Preset>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = Gson().toJson(carriers) // Convert list to JSON
        getPrefs(context).edit().putString(KEY_LAST_PRESETS, carriersJson).apply() // Save JSON as a string
    }

    fun getPresets(context: Context): List<StardustConfigurationParser.Preset>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = getPrefs(context).getString(KEY_LAST_PRESETS, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<StardustConfigurationParser.Preset>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<StardustConfigurationParser.Preset>>(carriersJson, type) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setCarriers(context: Context, carriers: List<Carrier>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = Gson().toJson(carriers) // Convert list to JSON
        getPrefs(context).edit().putString(KEY_LAST_CARRIERS, carriersJson).apply() // Save JSON as a string
    }

    fun getCarriers(context: Context): List<Carrier>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = getPrefs(context).getString(KEY_LAST_CARRIERS, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<Carrier>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<Carrier>>(carriersJson, type) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setCarriers1(context: Context, carriers: List<Carrier>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = Gson().toJson(carriers) // Convert list to JSON
        getPrefs(context).edit().putString(KEY_LAST_CARRIERS1, carriersJson).apply() // Save JSON as a string
    }

    fun getCarriers1(context: Context): List<Carrier>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = getPrefs(context).getString(KEY_LAST_CARRIERS1, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<Carrier>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<Carrier>>(carriersJson, type) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setCarriers2(context: Context, carriers: List<Carrier>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = Gson().toJson(carriers) // Convert list to JSON
        getPrefs(context).edit().putString(KEY_LAST_CARRIERS2, carriersJson).apply() // Save JSON as a string
    }

    fun getCarriers2(context: Context): List<Carrier>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = getPrefs(context).getString(KEY_LAST_CARRIERS2, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<Carrier>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<Carrier>>(carriersJson, type) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setCarriers3(context: Context, carriers: List<Carrier>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = Gson().toJson(carriers) // Convert list to JSON
        getPrefs(context).edit().putString(KEY_LAST_CARRIERS3, carriersJson).apply() // Save JSON as a string
    }

    fun getCarriers3(context: Context): List<Carrier>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val carriersJson = getPrefs(context).getString(KEY_LAST_CARRIERS3, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<Carrier>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<Carrier>>(carriersJson, type) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setLocationFormat (context: Context,locationFormat : String ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_LOCATION_FORMAT, locationFormat).apply()
    }

    fun getLocationFormat (context: Context) : String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getString(KEY_LOCATION_FORMAT, "") ?: ""
    }

    fun getAlertDest (context: Context) : String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getString(KEY_ALERT_DEST, "") ?: ""
    }

    fun setAlertDest (context: Context, dest : String)  {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_ALERT_DEST, dest).apply()

    }

    fun getKeyNameCrypto (context: Context) : String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefs(context).getString(KEY_KEY_NAME, "Default") ?: "Default"
    }

    fun setKeyNameCrypto (context: Context, dest : String)  {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putString(KEY_KEY_NAME, dest).apply()
    }

    fun getIsErased (context: Context) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefsPlugin(context).getBoolean(KEY_ERASE, false)
    }

    fun setIsErased (context: Context, isErased : Boolean)  {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefsPlugin(context).edit().putBoolean(KEY_ERASE, isErased).apply()
    }

    fun getIsManualLocation (context: Context) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return getPrefsPlugin(context).getBoolean(KEY_LOCATION_MANUAL, false)
    }

    fun setIsManualLocation (context: Context, isErased : Boolean)  {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefsPlugin(context).edit().putBoolean(KEY_LOCATION_MANUAL, isErased).apply()
    }

    fun setCodecType(context: Context, codecType: RecorderUtils.CODE_TYPE) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        getPrefs(context).edit().putInt(KEY_INPUT_CODEC, codecType.id).apply()
    }

    fun getCodecType(context: Context): RecorderUtils.CODE_TYPE {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val codecId = getPrefs(context).getInt(KEY_INPUT_CODEC, RecorderUtils.CODE_TYPE.CODEC2.id)
        when (codecId) {
            RecorderUtils.CODE_TYPE.CODEC2.id -> return RecorderUtils.CODE_TYPE.CODEC2
            RecorderUtils.CODE_TYPE.AI.id -> return RecorderUtils.CODE_TYPE.AI
            else -> return RecorderUtils.CODE_TYPE.CODEC2
        }
    }
}