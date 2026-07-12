package com.commcrete.stardust.util


import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.preference.PreferenceManager
import com.google.android.gms.location.LocationRequest
import com.commcrete.stardust.R
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.request_objects.model.license.License
import com.commcrete.stardust.request_objects.toJson
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.util.audio.RecorderUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.commcrete.stardust.ai.codec.WavTokenizerDecoder
import kotlin.collections.get
import androidx.core.content.edit

object SharedPreferencesUtil {
    private const val PACKAGE_NAME = "com.commcrete.bittell"
    private const val KEY_USER_ID = "user_info"
    private const val KEY_USER_OBJ = "user_obj"
    private const val KEY_INITIAL_DEVICE_ID = "initial_device_id"
    private const val KEY_CONNECTED_TO_UNKNOWN_DEVICE = "connected_to_unknown_device"
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
    private const val KEY_CODEC_HANDLE_GAIN = "handle_gain"
    private const val KEY_AUDIO_GAIN = "handle_ai_gain"
    private const val KEY_ENABLE_AUTO_GAIN_CONTROL = "enable_auto_gain_control"
    private const val KEY_ENABLE_NOISE_SUPPRESSOR = "enable_noise_suppressor"
    private const val KEY_ENABLE_ACOUSTIC_ECHO_CONTROL = "enable_acoustic_echo_control"
    private const val KEY_CODEC_RECORDING_TYPE = "recording_type"
    private const val KEY_AI_RECORDING_TYPE = "ai_recording_type"
    private const val KEY_BITTEL_BIT_SERVER = "enable_bittel_server"
    private const val KEY_ENABLE_PTT_SOUND = "enable_ptt_sound"
    private const val KEY_SELECT_CONNECTIVITY_OPTIONS = "select_connectivity_options"
    private const val KEY_BITTEL_ACK = "enable_bittel_ack"
    private const val KEY_PTT_TIMEOUT = "ptt_timeout"
    private const val KEY_SAVE_PTT_FILES = "save_ptt_files"
    private const val KEY_EXPORT_SESSION_DATA_ON_LOGOUT = "export_session_data_on_logout"

    //Record type Values

    val AUDIO_SOURCE_TO_KEY = mapOf(
        //MediaRecorder.AudioSource.DEFAULT to "Default",
        MediaRecorder.AudioSource.MIC to "Mic",
        //MediaRecorder.AudioSource.VOICE_CALL to "Voice Call",
        //MediaRecorder.AudioSource.CAMCORDER to "Camcorder",
        //MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice Communication",
        //MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice Recognition"
    )

    private val KEY_TO_AUDIO_SOURCE = AUDIO_SOURCE_TO_KEY.entries
        .associate { (k, v) -> v to k }

    //Location type Values
    private const val KEY_LOCATION_PRIORITY = "select_location_priority"
    private const val KEY_LOCATION_ACCURACY = "location_accuracy"
    private const val KEY_LOCATION_INTERVAL = "location_interval"
    private const val KEY_LOCATION_MANUAL = "location_manual"

    private const val KEY_IS_CONFIG_SAVED = "configSaved"

    //SOS Contacts
    private const val KEY_SOS_SELECTED_1 = "sos_selected_1"
    private const val KEY_SOS_SELECTED_2 = "sos_selected_2"
    private const val KEY_SOS_LAST_DESTINATIONS = "sos_last_destinations"

    private const val KEY_EQ_BAND = "eq_band_"

    private const val KEY_ADMIN_MODE = "admin_mode_type"
    private const val KEY_ADMIN_LOCAL_MODE = "admin_mode_type_name"

    private const val KEY_LAST_USER = "last_user"
    private const val KEY_ALERT_DEST = "alert_dest"

    private const val KEY_RSSI_SOURCE = "rssi_source"

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
    const val KEY_LAST_CARRIERS = "last_carriers"
    const val KEY_LAST_CARRIERS1 = "last_carriers1"
    const val KEY_LAST_CARRIERS2 = "last_carriers2"
    const val KEY_LAST_CARRIERS3 = "last_carriers3"
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

    //Files
    private const val KEY_RESILIENCE = "key_resilience"
    //Audio Ai
    private const val KEY_DEFAULT_AUDIO_DECODE_TYPE = "audio_ai_decode_type"
    private const val KEY_DEFAULT_AUDIO_MODEL_TYPE = "audio_ai_model_type"
    private const val KEY_NOISE_CANCELLATION_ENABLED = "voice_cancellation_enabled"

    private fun getPrefs(): SharedPreferences {
        return DataManager.appContext.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
    }

    private fun getPrefsPlugin(): SharedPreferences? {
        return DataManager.pluginContext?.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)

    }

    fun getUserID(): String? {
        return getPrefs().getString(KEY_USER_ID, null)
    }

    fun getPhoneNumber(): String? {
        return getPrefs().getString(KEY_PHONE_NUMBER, null)
    }

    fun savePhoneNumber(phoneNumber: String) {
        getPrefs().edit { putString(KEY_PHONE_NUMBER, phoneNumber) }
    }

    fun getPassword(): String? {
        return getPrefs().getString(KEY_PASSWORD, null)
    }

    fun savePassword(password: String) {
        getPrefs().edit { putString(KEY_PASSWORD, password) }
    }

    fun removePassword() : Boolean {
        getPrefs().edit { remove(KEY_PASSWORD) }
        return true
    }

    fun removePhone() : Boolean {
        getPrefs().edit { remove(KEY_PHONE_NUMBER) }
        return true
    }

    fun removeUserID() {
        getPrefs().edit { remove(KEY_USER_ID) }
    }

    fun setUserID(userId : String){
        getPrefs().edit { putString(KEY_USER_ID, userId) }
    }

    fun setBittelDevice(bittelDevice : String) {
        val isNewDeviceConnection = getBittelDevice() != bittelDevice
        if(isNewDeviceConnection) {
            getPrefs().edit { putString(KEY_BEETLE_DEVICE, bittelDevice) }
        }
        setConnectedToUnknownDevice(isNewDeviceConnection)
    }

    fun removeBittelDevice() : Boolean {
        getPrefs().edit { remove(KEY_BEETLE_DEVICE) }
        return true
    }

    fun setBittelDeviceName(bittelDeviceName : String){
        getPrefs().edit { putString(KEY_BEETLE_DEVICE_NAME, bittelDeviceName) }
    }

    fun getBittelDeviceName() : String {
        return getPrefs().getString(KEY_BEETLE_DEVICE_NAME, "") ?: ""
    }

    fun removeBittelDeviceName() : Boolean {
        getPrefs().edit { remove(KEY_BEETLE_DEVICE_NAME) }
        return true
    }

    fun setLicenses(licenses : String){
        getPrefs().edit { putString(KEY_LICENSES, licenses) }
    }

    fun getLicenses() : License?{
        val licensesString = getPrefs().getString(KEY_LICENSES, "")
        return Gson().fromJson(licensesString, License::class.java)
    }

    fun getConnectedToUnknownDevice(): Boolean {
        return getPrefs().getBoolean(KEY_CONNECTED_TO_UNKNOWN_DEVICE, false)
    }

    fun setConnectedToUnknownDevice(isNewConnection: Boolean) {
        return getPrefs().edit { putBoolean(KEY_CONNECTED_TO_UNKNOWN_DEVICE, isNewConnection) }
    }

    fun getUserInitialDeviceID() : String {
        return getPrefs().getString(KEY_INITIAL_DEVICE_ID, "") ?: ""
    }

    fun setUserInitialDeviceID(deviceId: String) {
        return getPrefs().edit { putString(KEY_INITIAL_DEVICE_ID, deviceId) }
    }

    fun getBittelDevice() : String?{
        return getPrefs().getString(KEY_BEETLE_DEVICE, "")
    }

    fun setFirebaseToken(token : String){
        getPrefs().edit { putString(KEY_FIREBASE_TOKEN, token) }
    }

    fun setAppUser(appUser : RegisterUser) {
        getPrefs().edit { putString(KEY_APP_USER, appUser.toJson()) }
        RegisteredUserUtils.updateRegisteredUser(appUser)
    }

    fun removeAppUser() : Boolean{
        RegisteredUserUtils.updateRegisteredUser(null)
        getPrefs().edit { remove(KEY_APP_USER) }
        return true
    }

    fun getAppUser() : RegisterUser? {
        val userString = getPrefs().getString(KEY_APP_USER, "")
        if(!userString.isNullOrEmpty()) {
            return Gson().fromJson(userString, RegisterUser::class.java)
        }
        return null
    }

    fun getEspPortSelected() : Boolean {
        return getPrefs().getBoolean(KEY_ESP_PORT, true)
    }

    fun setEspPort(isSelected : Boolean){
        getPrefs().edit { putBoolean(KEY_ESP_PORT, isSelected) }
    }

    fun getFirebaseToken() : String?{
        return getPrefs().getString(KEY_FIREBASE_TOKEN, "")
    }

    fun setUser(user : String?){
        getPrefs().edit { putString(KEY_USER_OBJ, user) }
    }

    fun removeUser () : Boolean {
        getPrefs().edit { remove(KEY_USER_OBJ) }
        return true
    }

    fun getUser() : User? {
        val userString = getPrefs().getString(KEY_USER_OBJ, "")
        if(!userString.isNullOrEmpty()) {
            val user = Gson().fromJson(userString, User::class.java)
            return user
        }
        return null
    }

    fun setDeveloperMode(isDeveloper : Boolean = false) {
        getPrefs().edit { putBoolean(KEY_DEVELOPER, isDeveloper) }
    }

    fun isDeveloperMode() : Boolean{
        return getPrefs().getBoolean(KEY_DEVELOPER, false)
    }

    private fun getPreferencesBoolean(key :String) : Boolean {
        return getPrefs().getBoolean(key, false)
    }

    private fun getPreferencesInt(key :String, default : Int = 0) : Int {
        return getPrefs().getInt(key, default)
    }

    private fun getPreferencesString(key :String, default : String = "") : String? {
        return getPrefs().getString(key, default)
    }

    fun getAudioGain() : Float{
        return getPrefs().getFloat( KEY_AUDIO_GAIN, 50.toFloat())
    }

    fun setAudioGain(gain: Float) {
        getPrefs().edit { putFloat(KEY_AUDIO_GAIN, gain) }
    }

    fun getNoiseSuppressorEnableState() : Boolean {
        return getPreferencesBoolean(KEY_ENABLE_NOISE_SUPPRESSOR)
    }

    fun setNoiseSuppressorEnableState(enabled: Boolean) {
        getPrefs().edit { putBoolean(KEY_ENABLE_NOISE_SUPPRESSOR, enabled) }
    }

    fun getAcousticEchoControl(): Boolean {
        return getPreferencesBoolean(KEY_ENABLE_ACOUSTIC_ECHO_CONTROL)
    }

    fun getCodecAudioSource(): Int {
        val key = getPreferencesString(KEY_CODEC_RECORDING_TYPE)
        return KEY_TO_AUDIO_SOURCE[key] ?: MediaRecorder.AudioSource.MIC
    }

    fun setCodecAudioSource(audioSource: Int) {
        val key = AUDIO_SOURCE_TO_KEY[audioSource]
        getPrefs().edit { putString(KEY_CODEC_RECORDING_TYPE, key) }
    }

    fun getAIAudioSource(): Int {
        val key = getPreferencesString(KEY_AI_RECORDING_TYPE)
        return KEY_TO_AUDIO_SOURCE[key] ?: MediaRecorder.AudioSource.MIC
    }

    fun setAIAudioSource(audioSource: Int) {
        val key = AUDIO_SOURCE_TO_KEY[audioSource]
        getPrefs().edit { putString(KEY_AI_RECORDING_TYPE, key) }
    }


    fun getEnablePttSound(): Boolean {
        return getPrefs().getBoolean(KEY_ENABLE_PTT_SOUND, true)
    }

    fun getIsStardustServerBitEnabled(): Boolean {
        return getPrefs().getBoolean(KEY_BITTEL_BIT_SERVER, false)
    }

    fun getConnectivityToggles(): MutableSet<String>? {
        val defaults = mutableSetOf(DataManager.appContext.getString(R.string.bluetooth))
        return getPrefs().getStringSet(KEY_SELECT_CONNECTIVITY_OPTIONS, defaults)
    }

    fun getConfigSaved(): Boolean {
        return getPreferencesBoolean(KEY_IS_CONFIG_SAVED)
    }

    fun setConfigSaved() {
        getPrefs().edit { putBoolean(KEY_IS_CONFIG_SAVED, true) }
    }

    fun isBittelAck(): Boolean {
        return getPrefs().getBoolean(KEY_BITTEL_ACK, false)
    }

    fun getLocationInterval(): Int {
        val value = getPreferencesString(KEY_LOCATION_INTERVAL, "4")
        return (value?.toInt() ?: 4) * 1000

    }

    fun setLocationInterval(interval: String) {
        getPrefs().edit().putString(KEY_LOCATION_INTERVAL, interval).apply()
    }

    fun getLocationPriority(): Int {
        val priority = getPreferencesString(KEY_LOCATION_PRIORITY, "100")
        if (priority == "100") {
            return LocationRequest.PRIORITY_HIGH_ACCURACY
        } else if (priority == "102") {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        } else if (priority == "104") {
            LocationRequest.PRIORITY_LOW_POWER
        }
        return LocationRequest.PRIORITY_HIGH_ACCURACY

    }

    fun getLocationAccuracy(): Int {
        val value = getPreferencesString(KEY_LOCATION_ACCURACY, "200")
        return value?.toInt() ?: 100
    }

    fun getSelectedSOSMain(): String {
        return getPreferencesString(KEY_SOS_SELECTED_1, "") ?: ""
    }

    fun getSelectedSOSSub(): String {
        return getPreferencesString(KEY_SOS_SELECTED_2, "") ?: ""
    }

    fun getEqBand(bandNum: Int): Int {
        val default = when (bandNum) {
            0 -> {
                -14
            }

            1 -> {
                -13
            }

            2 -> {
                0
            }

            3 -> {
                8
            }

            4 -> {
                -6
            }

            else -> {
                0
            }
        }
        return getPreferencesInt(KEY_EQ_BAND + bandNum, default) * 100
    }

    fun setExportDataOnLogout(save: Boolean) {
        getPrefs().edit { putBoolean(KEY_EXPORT_SESSION_DATA_ON_LOGOUT, save) }
    }

    fun getExportDataOnLogout(): Boolean {
        return getPrefs().getBoolean(KEY_EXPORT_SESSION_DATA_ON_LOGOUT, false)
    }

    /**
     * Use it from DataManager.updateSavePTTFilesRequired only!!!
     * */
    fun setSavePTTFiles(save: Boolean) {
        getPrefs().edit { putBoolean(KEY_SAVE_PTT_FILES, save) }
    }

    /**
     * Use it from DataManager.getSavePTTFilesRequired only!!!
     * */
    fun getSavePTTFiles(): Boolean {
        return getPrefs().getBoolean(KEY_SAVE_PTT_FILES, true)
    }

    fun getPTTTimeout(): Int {
        val value = getPreferencesInt(KEY_PTT_TIMEOUT, 45)
        return value.times(1000)
    }

    fun setLastUser(userId: String) {
        getPrefs().edit { putString(KEY_LAST_USER, userId) }
    }

    fun getLastUser(): String {
        return getPreferencesString(KEY_LAST_USER, "") ?: ""
    }

    fun setAdminMode(snifferMode: StardustConfigurationParser.SnifferMode) {
        getPrefs().edit { putInt(KEY_ADMIN_MODE, snifferMode.type) }
    }

    fun getAdminMode(): StardustConfigurationParser.SnifferMode {
        val type = getPrefs().getInt(KEY_ADMIN_MODE, 0)
        return StardustConfigurationParser.SnifferMode.entries[type]
    }

    fun getAdminLocalMode(): AdminUtils.AdminLocal {
        val context = DataManager.appContext
        val type =
            getPrefs().getString(KEY_ADMIN_LOCAL_MODE, context.getString(R.string.regular))
        when (type) {
            context.getString(R.string.regular) -> {
                return AdminUtils.AdminLocal.Regular
            }

            context.getString(R.string.admin) -> {
                return AdminUtils.AdminLocal.Admin
            }

            context.getString(R.string.superUser) -> {
                return AdminUtils.AdminLocal.SuperUser
            }
        }
        return AdminUtils.AdminLocal.Regular
    }

    fun setOutputDevice(outputDevice: String) {
        getPrefs().edit { putString(KEY_OUTPUT_DEFAULT, outputDevice) }
    }

    fun setInputDevice(inputDevice: String) {
        getPrefs().edit { putString(KEY_INPUT_DEFAULT, inputDevice) }
    }

    fun getOutputDevice(): Int {
        val audioSourceString = getPreferencesString(KEY_OUTPUT_DEFAULT)
        if (audioSourceString != null) {
            audioSourceString.let {
                return when (it) {
                    KEY_DEFAULT_AUDIO_OUTPUT -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    KEY_DEFAULT_AUDIO_OUTPUT_WIRED_HEADPHONES -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    KEY_DEFAULT_AUDIO_OUTPUT_USB_HEADSET -> AudioDeviceInfo.TYPE_USB_HEADSET
                    KEY_DEFAULT_AUDIO_OUTPUT_EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    KEY_DEFAULT_AUDIO_OUTPUT_BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_HDMI -> AudioDeviceInfo.TYPE_HDMI
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_BLUETOOTH_A2DP -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_REMOTE_SUBMIX -> AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_UNKNOWN -> AudioDeviceInfo.TYPE_UNKNOWN
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_WIRED_HEADSET -> AudioDeviceInfo.TYPE_WIRED_HEADSET
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_AUX_LINE -> AudioDeviceInfo.TYPE_AUX_LINE
                    KEY_DEFAULT_AUDIO_OUTPUT_TYPE_TEL -> AudioDeviceInfo.TYPE_TELEPHONY
                    else -> {
                        AudioDeviceInfo.TYPE_UNKNOWN
                    }
                }
            }
        } else {
            return AudioDeviceInfo.TYPE_UNKNOWN
        }
    }

    fun getInputDevice(): Int {
        val audioSourceString = getPreferencesString(KEY_INPUT_DEFAULT)
        if (audioSourceString != null) {
            audioSourceString.let {
                when (it) {
                    KEY_DEFAULT_AUDIO_INPUT -> return AudioDeviceInfo.TYPE_BUILTIN_MIC
                    KEY_DEFAULT_AUDIO_INPUT_DEFAULT -> return AudioDeviceInfo.TYPE_UNKNOWN
                    KEY_DEFAULT_AUDIO_INPUT_WIRED_HEADPHONES -> return AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    KEY_DEFAULT_AUDIO_INPUT_USB_HEADSET -> return AudioDeviceInfo.TYPE_USB_HEADSET
                    KEY_DEFAULT_AUDIO_INPUT_USB_DEVICE -> return AudioDeviceInfo.TYPE_USB_DEVICE
                    KEY_DEFAULT_AUDIO_INPUT_EARPIECE -> return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    KEY_DEFAULT_AUDIO_INPUT_BLUETOOTH -> return AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    else -> {
                        return AudioDeviceInfo.TYPE_UNKNOWN
                    }
                }
            }
        } else {
            return AudioDeviceInfo.TYPE_UNKNOWN
        }
    }

    fun setPresets(preset: List<StardustConfigurationParser.Preset>) {
        val presetJson = Gson().toJson(preset) // Convert list to JSON
        getPrefs().edit { putString(KEY_LAST_PRESETS, presetJson) } // Save JSON as a string
    }

    fun getPresets(): List<StardustConfigurationParser.Preset>? {
        val carriersJson = getPrefs().getString(KEY_LAST_PRESETS, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object :
                TypeToken<List<StardustConfigurationParser.Preset>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<StardustConfigurationParser.Preset>>(
                carriersJson,
                type
            ) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setCarriers(carriers: List<Carrier>, key: String = KEY_LAST_CARRIERS) {
        val gson = GsonBuilder()
            .registerTypeAdapter(Carrier::class.java, CarrierSerializer())
            .create()

        val carriersJson = gson.toJson(carriers)

        getPrefs().edit { putString(key, carriersJson) }
    }

    fun getCarriers(key: String = KEY_LAST_CARRIERS): List<Carrier>? {
        val carriersJson = getPrefs().getString(key, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type =
                object : TypeToken<List<Carrier>>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<List<Carrier>>(
                carriersJson,
                type
            ) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun getCarrier(key: String): Carrier? {
        val carriersJson = getPrefs().getString(key, null)

        return if (!carriersJson.isNullOrEmpty()) {
            val type = object : TypeToken<Carrier>() {}.type // Define the type of List<Carrier>
            Gson().fromJson<Carrier>(
                carriersJson,
                type
            ) // Convert JSON string back to List<Carrier>
        } else {
            null
        }
    }

    fun setLocationFormat(locationFormat: String) {
        getPrefs().edit { putString(KEY_LOCATION_FORMAT, locationFormat) }
    }

    fun getLocationFormat(): String {
        return getPrefs().getString(KEY_LOCATION_FORMAT, "") ?: ""
    }

    fun getAlertDest(): String {
        return getPrefs().getString(KEY_ALERT_DEST, "") ?: ""
    }

    fun setAlertDest(dest: String) {
        getPrefs().edit { putString(KEY_ALERT_DEST, dest) }

    }

    fun getRSSIReportSource(): String {
        return getPrefs().getString(KEY_RSSI_SOURCE, "") ?: ""
    }

    fun setRSSIReportSource(dest: String) {
        getPrefs().edit { putString(KEY_RSSI_SOURCE, dest) }

    }

    fun getKeyNameCrypto(): String {
        return getPrefs().getString(KEY_KEY_NAME, "Default") ?: "Default"
    }

    fun setKeyNameCrypto(dest: String) {
        getPrefs().edit { putString(KEY_KEY_NAME, dest) }
    }

    fun getIsErased(): Boolean {
        return getPrefsPlugin()?.getBoolean(KEY_ERASE, false) ?: false
    }

    fun setIsErased(isErased: Boolean) {
        getPrefsPlugin()?.edit()?.putBoolean(KEY_ERASE, isErased)?.apply()
    }

    fun getIsManualLocation(): Boolean {
        return getPrefs().getBoolean(KEY_LOCATION_MANUAL, false)
    }

    fun setIsManualLocation(isErased: Boolean) {
        getPrefs().edit { putBoolean(KEY_LOCATION_MANUAL, isErased) }
    }

    fun setCodecType(codecType: RecorderUtils.CODE_TYPE) {
        getPrefs().edit { putInt(KEY_INPUT_CODEC, codecType.id) }
    }

    fun setResilience(resilience: Resilience) {
        getPrefs().edit { putInt(KEY_RESILIENCE, resilience.value) }
    }

    fun getResilience(): Resilience {
        val savedLocal = getPrefs().getInt(KEY_RESILIENCE, 60)
        val resilience = when (savedLocal) {
            20 -> Resilience.Low
            60 -> Resilience.Medium
            120 -> Resilience.High
            else -> Resilience.Medium
        }
        return resilience
    }

    fun getCodecType(): RecorderUtils.CODE_TYPE {
        val codecId = getPrefs().getInt(KEY_INPUT_CODEC, RecorderUtils.CODE_TYPE.CODEC2.id)
        return when (codecId) {
            RecorderUtils.CODE_TYPE.CODEC2.id -> RecorderUtils.CODE_TYPE.CODEC2
            RecorderUtils.CODE_TYPE.AI.id -> RecorderUtils.CODE_TYPE.AI
            else -> RecorderUtils.CODE_TYPE.CODEC2
        }
    }

    @Deprecated("As there is no option to update this value from app now this function is unavailable")
    fun setAudioDecodeType(decodeMode: WavTokenizerDecoder.DecodeMode) {
//        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
//        prefs.edit()
//            .putString(KEY_DEFAULT_AUDIO_DECODE_TYPE, decodeMode.name) // save enum as string
//            .apply()
    }

    @Deprecated("As there is no option to update this value from app now it will return WavTokenizerDecoder.DecodeMode.Combined")
    fun getAudioDecodeType(): WavTokenizerDecoder.DecodeMode {
//        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
//        val saved = prefs.getString(KEY_DEFAULT_AUDIO_DECODE_TYPE, null)
//
//        return try {
//            if (saved != null) WavTokenizerDecoder.DecodeMode.valueOf(saved)
//            else WavTokenizerDecoder.DecodeMode.Combined   // default value
//        } catch (e: Exception) {
//            WavTokenizerDecoder.DecodeMode.Combined        // fallback if corrupted
//        }
        return WavTokenizerDecoder.DecodeMode.Combined
    }

    @Deprecated("As there is no option to update this value from app now this function is unavailable")
    fun setAudioModelType(model: WavTokenizerDecoder.ModelType) {
//        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
//        prefs.edit()
//            .putString(KEY_DEFAULT_AUDIO_MODEL_TYPE, model.name) // save enum as string
//            .apply()
    }

    @Deprecated("As there is no option to update this value from app now it will return WavTokenizerDecoder.ModelType.General")
    fun getAudioModelType(): WavTokenizerDecoder.ModelType {
//        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
//        val saved = prefs.getString(KEY_DEFAULT_AUDIO_MODEL_TYPE, null)
//
//        return try {
//            if (saved != null) WavTokenizerDecoder.ModelType.valueOf(saved)
//            else WavTokenizerDecoder.ModelType.General   // default value
//        } catch (e: Exception) {
//            WavTokenizerDecoder.ModelType.General        // fallback if corrupted
//        }
        return WavTokenizerDecoder.ModelType.General
    }

    /**
     * Persist the most recent list of SOS destination IDs (contact / chat /
     * device IDs the user broadcast SOS to). Stored as a JSON-serialised
     * `List<String>` so the order — typically "most-recent-first" — is
     * preserved across app restarts.
     *
     * Pass an empty list to clear the stored history.
     */
    fun saveLastSosDestinations(sosDestinations: List<String>) {
        val json = Gson().toJson(sosDestinations)
        getPrefs().edit { putString(KEY_SOS_LAST_DESTINATIONS, json) }
    }

    /**
     * Returns the most recent list of SOS destination IDs previously stored
     * via [saveLastSosDestinations], or an empty list when nothing has been
     * saved yet / the stored value is corrupted.
     */
    fun getLastSosDestinations(): List<String> {
        val json = getPrefs().getString(KEY_SOS_LAST_DESTINATIONS, null)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson<List<String>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}


