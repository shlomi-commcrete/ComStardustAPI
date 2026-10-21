package com.commcrete.stardust.util


import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.FunctionalitySelectionState
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.stardust.model.config.CurrentPreset
import com.commcrete.stardust.stardust.model.config.Preset
import com.commcrete.stardust.stardust.model.config.Xcvr
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustDeliveryType
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.reflect.Type
import kotlin.collections.forEach

object CarriersUtils {

    var carrierList : MutableLiveData<List<Carrier>> = MutableLiveData()

    private val carriersMap: MutableMap<Int, List<Carrier>> = mutableMapOf()

    fun setPresetsAfterChange(configPackage: StardustConfigurationPackage) {
        var index = 0
        carriersMap.clear()

        for (preset in configPackage.presets) {
            val carriers = getCarriersByPreset(preset)
            setLocalCarriersByPreset(preset.index, carriers)
            carriersMap.put(index, carriers)
            index ++
        }
    }

    /**
     * The presets are as saved, but each RD's F still comes from the [configPackage] that just
     * arrived: a saved list may predate [Carrier.fRange] or hold an F the device has since moved.
     */
    fun setPresetsWithoutChange(configPackage: StardustConfigurationPackage) {
        carriersMap.clear()
        for (i in 0..2) {
            val localCarriers = getLocalCarriersByPreset(i) ?: continue
            val synced = configPackage.presets.getOrNull(i)?.let { withFRanges(localCarriers, it) } ?: localCarriers
            // Carrier.equals ignores the F, so compare it directly.
            if (synced.map { it.fRange } != localCarriers.map { it.fRange }) setLocalCarriersByPreset(i, synced)
            carriersMap.put(i, synced)
        }
    }

    /** [carriers] (numbered by slot) with each one's F taken from [preset]'s transceiver in that slot. */
    internal fun withFRanges(carriers: List<Carrier>, preset: Preset): List<Carrier> = carriers.map { carrier ->
        val range = preset.xcvrList.getOrNull(carrier.index)?.let(::fRangeOf)
        if (range == carrier.fRange) carrier else carrier.copy(fFirst = range?.first, fLast = range?.last)
    }

    /**
     * The F (1-9) [xcvr] occupies: from its own F for as many as its bandwidth spans — the same
     * start and width [com.commcrete.stardust.stardust.model.config.LeaseCalculator] computes
     * leases from. Legacy firmware reports no bandwidth, and codes 1 and 7 span none; both still
     * name the transceiver's own F, so either is that single F.
     */
    internal fun fRangeOf(xcvr: Xcvr): IntRange {
        val first = xcvr.carrier.index + 1
        val span = (xcvr.bandwidthOption?.carriers ?: 1).coerceAtLeast(1)
        return first until first + span
    }

    fun updateCurrentPresetList(preset: CurrentPreset?) {
        val currentCarrierData = preset?.let { carriersMap[preset.value] } ?: listOf()
        Scopes.getMainCoroutine().launch {
            carrierList.value = currentCarrierData
        }
    }

    fun getCarriersByPreset(preset: Preset) : List<Carrier> {
        val carriers = mutableListOf<Carrier> ()

        for (i in 0..2) {
            val xcvr = preset.xcvrList.getOrNull(i) ?: continue
            val options = xcvr.getOptions().toMutableSet()
            val type = xcvr.carrier.type
            val range = fRangeOf(xcvr)
            carriers.add(
                Carrier(
                    index = i,
                    type = type,
                    presetActiveFunctionality = options,
                    fFirst = range.first,
                    fLast = range.last,
                )
            )
        }

        return carriers
    }

    fun setLocalCarrierList () : List<Carrier>?{
        // The synchronous mirror, not the posted LiveData — see ConfigurationUtils.currentPresetNow.
        val mutableList = getLocalCarriersByPreset(ConfigurationUtils.currentPresetNow?.value ?: 0)
        Scopes.getMainCoroutine().launch {
            mutableList?.let { carrierList.value = it }
        }
        return carrierList.value
    }

    fun getRadioToSend(carrier: Carrier? = null, functionalityType: FunctionalityType): Carrier? {

        val selectedCarrier = carrier?.takeIf { it.availableFunctionalities.contains(functionalityType) }
            ?: getDefaultCarrierForFunctionalityType(functionalityType)
            ?: return null

        return selectedCarrier
    }

    private fun getDefaultCarrierForFunctionalityType (functionalityType: FunctionalityType) : Carrier? {
        return carrierList.value?.find { it.activeFunctionalities.contains(functionalityType) }
    }


    fun updateFunctionalityToCarrier (carrierList: List<Carrier>, carrier: Carrier, functionality: FunctionalityType, isEnabled: Boolean) {
        if(isEnabled) {
            carrierList.forEach { other ->
                other.functionalityStateMap[functionality]?.updateSelectionStatus(other.index == carrier.index)
            }
        }
        else {
            carrierList.find { c -> c.index == carrier.index }?.functionalityStateMap?.get(functionality)?.updateSelectionStatus(false)
        }
        updateCarrierList(carrierList)
    }

    private fun updateCarrierList (mutableList : List<Carrier>) {
        setLocalCarriersByPreset((ConfigurationUtils.currentPreset.value?.value ?: 0), mutableList)
        carrierList.value = mutableList
    }

    fun getCarrierByControl(deliveryType: StardustDeliveryType): Carrier? {
        val carriers = carrierList.value
        if (carriers.isNullOrEmpty()) {
            Timber.w("Carrier list is empty or null")
            return null
        }

        val index = when (deliveryType) {
            StardustDeliveryType.RD1 -> 0
            StardustDeliveryType.RD2 -> 1
            StardustDeliveryType.RD3 -> 2
            StardustDeliveryType.RD4 -> 3
        }

        val carrier = carriers.getOrNull(index)

        if (carrier == null) {
            Timber.w(
                "No carrier found for deliveryType=%s (index=%d, carriersSize=%d)",
                deliveryType,
                index,
                carriers.size
            )
        }

        return carrier
    }

    private fun getLocalCarriersByPreset(presetIndex: Int) : List<Carrier>? {
        // Filter ST: lists saved before ST was dropped from the carrier list still contain it.
        return SharedPreferencesUtil.getCarriers(presetIndex)?.filter { it.type != CarrierType.ST }
    }

    private fun setLocalCarriersByPreset(presetIndex: Int, carriers: List<Carrier>) {
        SharedPreferencesUtil.setCarriers(carriers, presetIndex)
    }


    fun reset() {
        carriersMap.clear()
        carrierList.postValue(listOf())
    }
}

/**
 * [index] is the RD's slot in the preset on the send side (getCarriers, getRadioToSend), but the
 * transceiver's F (0-8) on a radio event's carrier — see ConfigurationUtils.setStardustCarrierFromEvent.
 * [fFirst]..[fLast] is the F an RD carrier occupies as shown to the operator (1-9) — see
 * [fRange]; both null until a configuration sets them.
 */
data class Carrier (
    val index : Int,
    var type : CarrierType,
    private var presetActiveFunctionality: Set<FunctionalityType>? = null,
    // Two ints rather than an IntRange: a Carrier is persisted through Gson, which round-trips
    // plain fields and not Kotlin's range classes.
    val fFirst: Int? = null,
    val fLast: Int? = null,
) {

    /** The F this RD occupies, e.g. 1..4; a single F is first == last. Not persisted — no field. */
    val fRange: IntRange?
        get() = if (fFirst != null && fLast != null) fFirst..fLast else null

    val deliveryType: StardustDeliveryType = StardustDeliveryType.entries.find { it.ordinal == index } ?: StardustDeliveryType.RD1

    @Transient
    private var _functionalityStateMap: Map<FunctionalityType, FunctionalityState>? = null

    val functionalityStateMap: Map<FunctionalityType, FunctionalityState>
        get() {
            if (_functionalityStateMap == null) {
                _functionalityStateMap = initFunctionalityStateMap()
            }
            return _functionalityStateMap!!
        }

    val activeFunctionalities: Set<FunctionalityType>
        get() {
            return functionalityStateMap
                .filterValues { state -> state.selectionState == FunctionalitySelectionState.SELECTED }
                .keys
        }

    val availableFunctionalities: Set<FunctionalityType>
        get() {
            return functionalityStateMap
                .filterValues { state -> state.selectionState != FunctionalitySelectionState.DISABLED }
                .keys
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true // Reference equality
        if (other !is Carrier) return false // Type check

        return index == other.index
               && type == other.type
               && deliveryType == other.deliveryType
    }

    override fun hashCode(): Int {
        return index.hashCode() * 31 +
                type.hashCode() * 31 +
                deliveryType.hashCode()
    }

    private fun initFunctionalityStateMap(): Map<FunctionalityType, FunctionalityState> {

        val result = type.getAllowedFunctionalityOptions().associateWith { functionality ->
            val limitation = ConfigurationUtils.licensedFunctionalities[functionality]

            FunctionalityState(limitation).apply {
                if(limitation == LimitationType.ENABLED) {
                    selectionState = if(presetActiveFunctionality?.contains(functionality) == true) FunctionalitySelectionState.SELECTED else FunctionalitySelectionState.UNSELECTED
                }
            }
        }
        return result
    }

    // for license 0-1 -> save preset as no active functionalities would be found
    fun updatePresetActiveFunctionality() {
        if(!isInLimitedFunctionality()) presetActiveFunctionality = activeFunctionalities
    }

    private fun isInLimitedFunctionality(): Boolean {
        return activeFunctionalities.isEmpty()
                && functionalityStateMap.values.find { it.selectionState != FunctionalitySelectionState.DISABLED } == null
    }


}


class CarrierSerializer : JsonSerializer<Carrier> {

    override fun serialize(
        src: Carrier?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement? {
        src?.updatePresetActiveFunctionality()
        return Gson().toJsonTree(src)
    }
}

data class FunctionalityState(val limitation: LimitationType?) {

    var onSelectionChanged: ((selection: FunctionalitySelectionState) -> Unit)? = null

    var selectionState: FunctionalitySelectionState = updateSelectionState(FunctionalitySelectionState.SELECTED)
        set(value) {
            val newState = updateSelectionState(value)
            if (field == newState) return  // no change, skip

            field = newState
            onSelectionChanged?.invoke(newState)
        }

    private fun updateSelectionState(state: FunctionalitySelectionState): FunctionalitySelectionState {
        return when(limitation) {
            LimitationType.ENABLED -> state
            else -> FunctionalitySelectionState.DISABLED
        }
    }

    fun updateSelectionStatus(
        isActive: Boolean
    ) {

        if (selectionState == FunctionalitySelectionState.DISABLED) return

        selectionState = if (isActive) {
            FunctionalitySelectionState.SELECTED
        } else {
            FunctionalitySelectionState.UNSELECTED
        }
    }


}


