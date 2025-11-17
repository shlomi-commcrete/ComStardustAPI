package com.commcrete.stardust.stardust

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.BleManager.ConnectionStatus
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.stardust.model.OpenStardustControlByte
import com.commcrete.stardust.stardust.model.StardustAddressesPackage
import com.commcrete.stardust.stardust.model.StardustAddressesParser
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.AdminUtils
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.LicenseLimitationsUtil
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

@SuppressLint("StaticFieldLeak")
object StardustInitConnectionHandler {

    enum class State {
        IDLE,
        REQUESTING_ADDRESSES,          // 1) request address
        UPDATING_SMARTPHONE_ADDR,      // 2) update address
        DELETING_GROUPS,               // 3) delete groups
        ADDING_GROUPS,                 // 4) add groups
        READING_CONFIGURATION,         // 5) get configuration
        UPDATING_ADMIN_MODE,           // 6) update admin mode
        DONE, CANCELED,
        RUNNING,
        SEARCHING,
        NO_LICENSE,
        ENCRYPTION_KEY_ERROR
    }

    private const val MAX_ATTEMPTS = 3
    private const val STEP_TIMEOUT_MS = 15_000L
    private val attempts: MutableMap<State, Int> = mutableMapOf()
    private val ctx: Context get() = DataManager.context
    private val conn: ClientConnection get() = DataManager.getClientConnection(ctx)
    private var timeoutJob: Job? = null

    interface InitConnectionListener {

        fun onInitFailed(state: State, reason: String) {}
        fun onInitDone(state: State) {}
        fun running(state: State) {}
    }


    var listener: InitConnectionListener? = null

    private var state = State.IDLE
        set(value) {
            field = value
            DataManager.getCallbacks()?.onDeviceInitialized(value)
        }

    val isRunning: Boolean
        get() = state !in setOf(State.IDLE, State.SEARCHING, State.DONE, State.CANCELED)

    // ───────────────────────── Lifecycle ─────────────────────────

    fun start() {
        if (isRunning) return
        onInitRunning()
        attempts.clear()
        transitionTo(State.REQUESTING_ADDRESSES) { sendGetAddresses() }
    }

    fun cancel() {
        state = State.CANCELED

        timeoutJob?.cancel()
        timeoutJob = null
        Timber.tag("InitHandler").d("Init flow canceled")
    }

    private fun stop() {
        state = State.DONE

        timeoutJob?.cancel()
        timeoutJob = null
        Timber.tag("InitHandler").d("Init flow done")
        onInitDone()

    }

    /**
     * Try to consume package. Return true if consumed.
     */
    fun onIncoming(p: StardustPackage): Boolean {
        Timber.tag("InitHandler").d("isRunning=$isRunning state=$state op=${p.stardustOpCode}")
        if (!isRunning) return false

        when (p.stardustOpCode) {

            // 1) Request address → expect GET_ADDRESSES
            StardustPackageUtils.StardustOpCode.GET_ADDRESSES -> if (state == State.REQUESTING_ADDRESSES) {
                timeoutJob?.cancel()
                handleAddressesReceived(p); return true
            }

            // 2) Update address → expect UPDATE_ADDRESS_RESPONSE (ACK)
            StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS_RESPONSE -> if (state == State.UPDATING_SMARTPHONE_ADDR) {
                handleAckOrRetry(
                    p,
                    onAck = { afterUpdateAddressAck() },
                    onRetry = {
                        lastAddresses?.let { sendUpdateSmartphoneAddress(it) }
                            ?: run {
                                state = State.ENCRYPTION_KEY_ERROR
                                failAndStop("No addresses cached") }
                    }
                ); return true
            }

            // 3) Delete groups → expect DELETE_GROUPS_RESPONSE (ACK)
            StardustPackageUtils.StardustOpCode.DELETE_GROUPS_RESPONSE -> if (state == State.DELETING_GROUPS) {
                handleAckOrRetry(
                    p,
                    onAck = { transitionTo(State.ADDING_GROUPS) { sendAddGroups() } },
                    onRetry = { sendDeleteGroups() }
                ); return true
            }

            // 4) Add groups → expect ADD_GROUPS_RESPONSE (ACK)
            StardustPackageUtils.StardustOpCode.ADD_GROUPS_RESPONSE -> if (state == State.ADDING_GROUPS) {
                handleAckOrRetry(
                    p,
                    onAck = { transitionTo(State.READING_CONFIGURATION) { requestConfiguration() } },
                    onRetry = { sendAddGroups() }
                ); return true
            }

            // 5) Get configuration → expect READ_CONFIGURATION_RESPONSE (or READ_STATUS echo)
            StardustPackageUtils.StardustOpCode.READ_CONFIGURATION_RESPONSE,
            StardustPackageUtils.StardustOpCode.READ_STATUS -> if (state == State.READING_CONFIGURATION) {
                timeoutJob?.cancel()
                handleConfiguration(p); return true
            }

            // 6) Update admin mode → expect SET_ADMIN_MODE_RESPONSE (ACK)
            StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE_RESPONSE -> if (state == State.UPDATING_ADMIN_MODE) {
                handleAckOrRetry(
                    p,
                    onAck = { finishAdminModeUpdate() },
                    onRetry = { sendUpdateAdminMode() }
                ); return true
            }

            // Optional: capture version anytime
            StardustPackageUtils.StardustOpCode.RECEIVE_VERSION -> {
                handleVersion(p); return false
            }

            else -> {}
        }
        return false
    }

    // ───────────────────────── State helpers ─────────────────────────

    private fun transitionTo(next: State, send: () -> Unit) {
        onInitRunning()

        state = next
        val n = (attempts[next] ?: 0) + 1
        attempts[next] = n
        Timber.tag("InitHandler").d("Step $next - attempt $n/$MAX_ATTEMPTS")
        send()
        startTimeoutFor(next)
    }

    private fun retryOrFail(send: () -> Unit) {
        state?.let { state ->
            val n = (attempts[state] ?: 0) + 1
            if (n > MAX_ATTEMPTS) {
                failAndStop("Step $state exceeded $MAX_ATTEMPTS attempts")
                return
            }
            attempts[state] = n
            Timber.tag("InitHandler").w("Retrying $state (attempt $n/$MAX_ATTEMPTS)")
            send()
            startTimeoutFor(state)
        }
    }

    private fun startTimeoutFor(step: State) {
        timeoutJob?.cancel()
        timeoutJob = Scopes.getMainCoroutine().launch {
            try {
                // awaitCancellation is clearer than delay(Long.MAX_VALUE)
                withTimeout(STEP_TIMEOUT_MS) { awaitCancellation() }
            } catch (e: TimeoutCancellationException) {
                // Only act if we're still in the same step (avoid stale callbacks)
                if (state != step) return@launch
                Timber.tag("InitHandler").w("$step timeout")
                when (step) {
                    State.REQUESTING_ADDRESSES -> retryOrFail { sendGetAddresses() }
                    State.UPDATING_SMARTPHONE_ADDR -> retryOrFail {
                        lastAddresses?.let { sendUpdateSmartphoneAddress(it) }
                            ?: failAndStop("No addresses cached")
                    }
                    State.DELETING_GROUPS -> retryOrFail { sendDeleteGroups() }
                    State.ADDING_GROUPS -> retryOrFail { sendAddGroups() }
                    State.READING_CONFIGURATION -> retryOrFail { requestConfiguration() }
                    State.UPDATING_ADMIN_MODE -> retryOrFail { sendUpdateAdminMode() }
                    else -> {}
                }
            } catch (e: CancellationException) {
                e.printStackTrace()
                // Normal cancellation because the step advanced or flow was canceled — do nothing
            }
        }
    }

    private fun handleAckOrRetry(
        p: StardustPackage,
        onAck: () -> Unit,
        onRetry: () -> Unit
    ) {
        timeoutJob?.cancel()
        if (p.isAck()) {
            Timber.tag("InitHandler").d("$state ACK")
            onAck()
        } else {
            Timber.tag("InitHandler").w("$state NACK")
            retryOrFail(onRetry)
        }
    }

    private fun failAndStop(reason: String) {
        Timber.tag("InitHandler").w("Init flow failed: $reason")
        onInitFailed(reason)
        cancel()
    }

    // ───────────────────────── Flow steps ─────────────────────────

    // Cache addresses after step 1 so we can retry step 2
    private var lastAddresses: StardustAddressesPackage? = null

    // 1) Request address
    private fun sendGetAddresses() {
        // If you can actively request addresses, SEND it here:
        val (src, dst) = requireSrcDstOrNull() ?: (null to null)
        if (src != null && dst != null) {
            val pkg = StardustPackageUtils.getStardustPackage(
                source = src,
                destenation = dst,
                stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADDRESS
            )
            pkg.openControlByte.stardustCryptType = OpenStardustControlByte.StardustCryptType.DECRYPTED
            conn.addMessageToQueue(pkg)
            Timber.tag("InitHandler").d("Sent GET_ADDRESSES")
        } else {
            Timber.tag("InitHandler").d("Waiting for GET_ADDRESSES…")
        }
    }

    private fun handleAddressesReceived(p: StardustPackage) {
        val addresses = StardustAddressesParser().parseAddresses(p)
            ?: return failAndStop("Failed to parse addresses")
        lastAddresses = addresses
        registerBittel(addresses.stardustID)
        transitionTo(State.UPDATING_SMARTPHONE_ADDR) { sendUpdateSmartphoneAddress(addresses) }
    }

    // 2) Update address
    private fun sendUpdateSmartphoneAddress(addr: StardustAddressesPackage) {
        val user = SharedPreferencesUtil.getAppUser(ctx) ?: return failAndStop("No app user")
        val appId = user.appId ?: return failAndStop("No appId")
        val payload = arrayListOf<Int>().apply {
            addAll(StardustPackageUtils.hexStringToByteArray(appId))
            add(0); add(0); add(0); add(0)
            add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
        }
        val pkg = StardustPackageUtils.getStardustPackage(
            source = appId,
            destenation = addr.stardustID,
            stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS,
            data = payload.toIntArray().toTypedArray()
        )
        conn.addMessageToQueue(pkg)
        Timber.tag("InitHandler").d("Sent UPDATE_ADDRESS (smartphone) to ${addr.stardustID}")
    }

    private fun afterUpdateAddressAck() {
        // Your existing local side-effects:
        GroupsUtils.deleteAllGroups(ctx)
        DataManager.getClientConnection(ctx).removeConnectionTimer()
        transitionTo(State.DELETING_GROUPS) { sendDeleteGroups() }
    }

    // 3) Delete groups
    private fun sendDeleteGroups() {
        val (src, dst) = requireSrcDst() ?: return
        val payload = buildDeleteGroupsPayload() // TODO: replace with your real payload
        val pkg = StardustPackageUtils.getStardustPackage(
            source = src, destenation = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_DELETE_ALL_GROUPS,
            data = payload
        )
        conn.addMessageToQueue(pkg)
        Timber.tag("InitHandler").d("Sent DELETE_GROUPS")
    }

    // 4) Add groups
    private fun sendAddGroups() {
        Scopes.getDefaultCoroutine().launch {
            val payload = buildAddGroupsPayload()
            val (src, dst) = requireSrcDst() ?: return@launch
            val pkg = StardustPackageUtils.getStardustPackage(
                source = src, destenation = dst,
                stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADD_GROUPS,
                data = payload
            )
            conn.addMessageToQueue(pkg)
            Timber.tag("InitHandler").d("Sent ADD_GROUPS")
        }
    }

    // 5) Get configuration
    private fun requestConfiguration() {
        val (src, dst) = requireSrcDst() ?: return
        val pkg = StardustPackageUtils.getStardustPackage(
            source = src,
            destenation = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.READ_STATUS
        )
        conn.addMessageToQueue(pkg)
        Timber.tag("InitHandler").d("Sent READ_STATUS for configuration")
    }

    private fun handleConfiguration(p: StardustPackage) {
        val cfg = StardustConfigurationParser().parseConfiguration(p)
            ?: return retryOrFail { requestConfiguration() } // parse failure → retry step 5
        Scopes.getMainCoroutine().launch {
            ConfigurationUtils.bittelConfiguration.value = cfg
        }
        ConfigurationUtils.licensedFunctionalities = LicenseLimitationsUtil().createSupportedFunctionalitiesByLicenseType(cfg.licenseType)
        ConfigurationUtils.setConfigFile(cfg)
        ConfigurationUtils.setDefaults(ctx)

        transitionTo(State.UPDATING_ADMIN_MODE) { sendUpdateAdminMode() }
    }

    // 6) Update admin mode
    private fun sendUpdateAdminMode() {
        val (src, dst) = requireSrcDst() ?: return
        val pkg = StardustPackageUtils.getStardustPackage(
            source = src,
            destenation = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE
        )
        conn.addMessageToQueue(pkg)
        Timber.tag("InitHandler").d("Sent SET_ADMIN_MODE")
        finishAdminModeUpdate()
    }

    private fun finishAdminModeUpdate() {
        AdminUtils.updateBittelAdminMode()
        if (BleManager.isUsbEnabled()) {
            BittelUsbManager2.updateBlePort()
            Timber.tag("startUpdatingPort").d("updateUsbPort (init)")
        } else if (BleManager.isBluetoothEnabled()) {
            DataManager.getClientConnection(ctx).updateBlePort()
            Timber.tag("startUpdatingPort").d("updateBlePort (init)")
        }
        stop()
    }

    // ───────────────────────── Utilities ─────────────────────────

    private fun registerBittel(bittelId: String) {
        val savedUser = SharedPreferencesUtil.getAppUser(ctx) ?: return
        val deviceName = SharedPreferencesUtil.getBittelDeviceName(ctx) ?: return
        if (BleManager.isBluetoothEnabled() || BleManager.isUsbEnabled()) {
            val newUser = RegisterUser(
                displayName = savedUser.displayName,
                licenseType = "",
                phone = savedUser.phone,
                location = arrayOf(),
                bittelId = bittelId,
                bittelName = deviceName,
                bittelMacAddress = deviceName,
                appId = savedUser.appId,
                token = savedUser.token
            )
            savedUser.appId?.let { SharedPreferencesUtil.setAppUser(ctx, newUser) }
            Timber.tag("InitHandler").d("Registered Bittel with id=$bittelId name=$deviceName")
        }
    }

    private fun handleVersion(p: StardustPackage) {
        Scopes.getMainCoroutine().launch {
            ConfigurationUtils.bittelVersion.value = p.getDataAsString()
        }
    }

    private fun requireSrcDst(): Pair<String, String>? {
        val u = SharedPreferencesUtil.getAppUser(ctx) ?: return null.also { failAndStop("No app user") }
        val src = u.appId ?: return null.also { failAndStop("No appId") }
        val dst = u.bittelId ?: return null.also { failAndStop("No bittelId") }
        return src to dst
    }
    private fun requireSrcDstOrNull(): Pair<String?, String?>? {
        val u = SharedPreferencesUtil.getAppUser(ctx) ?: return null
        return u.appId to "1"
    }

    // Stub payload builders — replace with real content
    private fun buildDeleteGroupsPayload(): Array<Int> {
        val intData = arrayListOf<Int>()
        intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
        return intData.toIntArray().toTypedArray()
    }
    private suspend fun buildAddGroupsPayload(): Array<Int> {
        return withContext(Dispatchers.IO) {
            val groupsList = ChatsRepository(
                ChatsDatabase.getDatabase(DataManager.context).chatsDao()
            ).getAllGroupIds()

            val intData = arrayListOf<Int>()
            for (group in groupsList) {
                intData.addAll(StardustPackageUtils.hexStringToByteArray(group).reversedArray())
            }
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
            intData.toIntArray().toTypedArray().reversedArray()
        }
    }

    private fun onInitFailed(reason: String) {
        val resultState = state.takeIf { it == State.ENCRYPTION_KEY_ERROR } ?: State.CANCELED
        DataManager.getCallbacks()?.onDeviceInitialized(resultState)
        listener?.onInitFailed(resultState, reason)
    }

    private fun onInitDone() {
        val resultState = when {
            ConfigurationUtils.bittelConfiguration.value?.licenseType?.equals(LicenseType.UNDEFINED) == true -> State.NO_LICENSE
            else -> State.DONE
        }
        state = resultState
        DataManager.getCallbacks()?.onDeviceInitialized(resultState)
        listener?.onInitDone(resultState)
    }

    private fun onInitRunning () {
        val resultState = State.RUNNING
        state = resultState
        DataManager.getCallbacks()?.onDeviceInitialized(resultState)
        listener?.running(resultState)
    }

    fun hasConnectionError(): Boolean {
        return state in setOf(State.CANCELED, State.ENCRYPTION_KEY_ERROR, State.NO_LICENSE)
    }

    fun isConnected(): Boolean {
        return hasConnectionError() || (state == State.DONE && BleManager.connectionStatus.value != ConnectionStatus.DISCONNECTED)
    }

    fun updateConnectionState(newState: State) {
        state = newState
    }

}