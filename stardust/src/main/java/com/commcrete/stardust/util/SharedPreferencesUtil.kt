package com.commcrete.stardust.util

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.preference.PreferenceManager
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.request_objects.model.license.License
import com.commcrete.stardust.request_objects.toJson
import com.example.mylibrary.R
import com.google.android.gms.location.LocationRequest
import com.google.gson.Gson

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

    private const val KEY_IS_CONFIG_SAVED = "configSaved"

    //SOS Contacts
    private const val KEY_SOS_SELECTED_1 = "sos_selected_1"
    private const val KEY_SOS_SELECTED_2 = "sos_selected_2"

    private const val KEY_EQ_BAND = "eq_band_"
    private fun getPrefs(context: Context): SharedPreferences {
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
        return preferences.getBoolean(key, false)
    }

    private fun getPreferencesInt (context: Context, key :String, default : Int = 0) : Int {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getInt(key, default)
    }

    private fun getPreferencesString (context: Context, key :String, default : String = "") : String? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString(key, default)
    }

    fun getGain(context: Context) : Float{
        return getPreferencesInt(context, KEY_HANDLE_GAIN).toFloat()
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
                    KEY_RECORDING_TYPE_VOICE_CALL -> return MediaRecorder.AudioSource.VOICE_CALL
                    KEY_RECORDING_TYPE_VOICE_COMMUNICATION -> return MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    KEY_RECORDING_TYPE_VOICE_RECOGNITION -> return MediaRecorder.AudioSource.VOICE_RECOGNITION
                    else -> { return MediaRecorder.AudioSource.DEFAULT }
                }
            }
        }else {
            return MediaRecorder.AudioSource.DEFAULT
        }
    }

    fun getEnablePttSound (context: Context) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getBoolean(KEY_ENABLE_PTT_SOUND, true)
    }

    fun getIsStardustServerBitEnabled(context: Context) : Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getBoolean(KEY_BITTEL_BIT_SERVER, false)
    }

    fun getConnectivityToggles (context: Context) : MutableSet<String>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val defaults = mutableSetOf( context.getString(R.string.bluetooth))
        return preferences.getStringSet(KEY_SELECT_CONNECTIVITY_OPTIONS, defaults)
    }

    fun getConfigSaved (context: Context) : Boolean {
        return getPreferencesBoolean(context, KEY_IS_CONFIG_SAVED)
    }

    fun setConfigSaved (context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean(KEY_IS_CONFIG_SAVED, true).apply()
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
        preferences.edit().putString(KEY_LOCATION_INTERVAL, interval).apply()
        LocationUtils.updatedLocationPullParams()
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
        return getPreferencesInt(context, KEY_EQ_BAND+bandNum, 0) *100
    }

    fun getPTTTimeout (context: Context) : Int {
        val value = getPreferencesInt(context, KEY_PTT_TIMEOUT, 45)
        return value.times(1000)
    }
}