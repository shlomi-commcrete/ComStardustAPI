package com.commcrete.stardust.util

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.FunctionalitySelectionState
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustConfigurationParser.StardustTypeFunctionality
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustDeliveryType
import com.commcrete.stardust.util.SharedPreferencesUtil.KEY_LAST_CARRIERS1
import com.commcrete.stardust.util.SharedPreferencesUtil.KEY_LAST_CARRIERS2
import com.commcrete.stardust.util.SharedPreferencesUtil.KEY_LAST_CARRIERS3
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
    var carrierList1 : List<Carrier> = listOf()
    var carrierList2 : List<Carrier> = listOf()
    var carrierList3 : List<Carrier> = listOf()

    fun setPresetsAfterChange (bittelConfigurationPackage: StardustConfigurationPackage) {
        var index = 0
        for (preset in bittelConfigurationPackage.presets) {
            val list = getCarrierLisByPreset(bittelConfigurationPackage, preset)
            setLocalCarriersByPreset((preset.index), list, DataManager.context)
            if(index == 0) {
                carrierList1 = list
            } else if (index == 1) {
                carrierList2 = list
            } else if (index == 2) {
                carrierList3 = list
            }
            index ++
        }
    }

    fun setPresetsWithoutChange () {
        getLocalCarriersByPreset(0,DataManager.context)?.let {
            carrierList1 = it
        }
        getLocalCarriersByPreset(1,DataManager.context)?.let {
            carrierList2 = it
        }
        getLocalCarriersByPreset(2,DataManager.context)?.let {
            carrierList3 = it
        }
    }

    fun updateCurrentPresetList (preset: StardustConfigurationParser.CurrentPreset) {
        Scopes.getMainCoroutine().launch {
            when (preset) {
                StardustConfigurationParser.CurrentPreset.PRESET1 -> carrierList.value = carrierList1
                StardustConfigurationParser.CurrentPreset.PRESET2 -> carrierList.value = carrierList2
                StardustConfigurationParser.CurrentPreset.PRESET3 -> carrierList.value = carrierList3
            }
        }
    }

    fun getCarrierLisByPreset(bittelConfigurationPackage: StardustConfigurationPackage, preset: StardustConfigurationParser.Preset?) : List<Carrier> {
        val mutableList : MutableList<Carrier> = arrayListOf()
        val radios = bittelConfigurationPackage.getCurrentRadios(preset?.currentPreset)
        val defaults1 = preset?.xcvrList?.get(0)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        val defaults2 = preset?.xcvrList?.get(1)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        val defaults3 = preset?.xcvrList?.get(2)?.getOptions ()?.toMutableSet() ?: mutableSetOf()

        mutableList.add(Carrier(0, radios.xcvr1,  "RD1", preset?.xcvrList?.get(0)?.carrier, presetActiveFunctionality = defaults1))
        mutableList.add(Carrier(1, radios.xcvr2,  "RD2", preset?.xcvrList?.get(1)?.carrier, presetActiveFunctionality = defaults2))
        mutableList.add(Carrier(2, radios.xcvr3,  "RD3", preset?.xcvrList?.get(2)?.carrier, presetActiveFunctionality = defaults3))
        mutableList.add(Carrier(3, StardustConfigurationParser.StardustTypeFunctionality.ST,  "RD4"))
        return mutableList
    }

    fun setLocalCarrierList () : List<Carrier>?{
        val mutableList = getLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), DataManager.context)
        Scopes.getMainCoroutine().launch {
            mutableList?.let { carrierList.value = it }
        }
        return carrierList.value
    }

    fun getRadioToSend (carrier: Carrier? = null, functionalityType: FunctionalityType) :
            Pair<Carrier, StardustDeliveryType>? {

        val selectedCarrier = carrier?.takeIf { it.availableFunctionalities.contains(functionalityType) }
            ?: getDefaultCarrierForFunctionalityType(functionalityType)
            ?: return null

        val deliveryType = when (selectedCarrier.index) {
            0 -> StardustDeliveryType.RD1
            1 -> StardustDeliveryType.RD2
            2 -> StardustDeliveryType.RD3
            3 -> StardustDeliveryType.RD4
            else -> StardustDeliveryType.RD1 // Default case
        }

//        when (functionalityType) {
//            FunctionalityType.REPORTS -> {
//                if (carrier?.type == StardustTypeFunctionality.ST) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//            FunctionalityType.TEXT -> {
//                if (carrier?.type == StardustTypeFunctionality.ST) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//            FunctionalityType.LOCATION -> {
//                if (carrier?.type == StardustTypeFunctionality.ST) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//            FunctionalityType.PTT -> {
//                if (carrier?.type != StardustTypeFunctionality.HR) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//            FunctionalityType.BFT -> {
//                if (carrier?.type != StardustTypeFunctionality.HR) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//            FunctionalityType.FILE -> {
//                if (carrier?.type == StardustTypeFunctionality.LR) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//            FunctionalityType.IMAGE -> {
//                if (carrier?.type == StardustTypeFunctionality.LR) {
//                    return getDefaultRadio(functionalityType)
//                }
//            }
//
//            FunctionalityType.ACK, FunctionalityType.SOS -> return null
//        }
        return Pair(selectedCarrier, deliveryType)
    }

    private fun getDefaultRadio ( functionalityType: FunctionalityType) :
            Pair<Carrier, StardustDeliveryType>? {

        val selectedCarrier = getDefaultCarrierForFunctionalityType(functionalityType)
        if(selectedCarrier == null) return null

        val deliveryType = when (selectedCarrier.index) {
            0 -> StardustDeliveryType.RD1
            1 -> StardustDeliveryType.RD2
            2 -> StardustDeliveryType.RD3
            3 -> StardustDeliveryType.RD4
            else -> StardustDeliveryType.RD1 // Default case
        }
        return Pair(selectedCarrier, deliveryType)
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
        setLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), mutableList, DataManager.context)
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

    private fun getLocalCarriersByPreset (preset : Int, context: Context) : List<Carrier>? {
        val local = when (preset) {
            0 -> {SharedPreferencesUtil.getCarriers(context, KEY_LAST_CARRIERS1)}
            1 -> {SharedPreferencesUtil.getCarriers(context, KEY_LAST_CARRIERS2)}
            2 -> {SharedPreferencesUtil.getCarriers(context, KEY_LAST_CARRIERS3)}
            else -> { null}
        }
        return local
    }

    private fun setLocalCarriersByPreset (preset : Int, carriers: List<Carrier>, context: Context) {
        when (preset) {
            0 -> {SharedPreferencesUtil.setCarriers(context, carriers, SharedPreferencesUtil.KEY_LAST_CARRIERS1)}
            1 -> {SharedPreferencesUtil.setCarriers(context, carriers, SharedPreferencesUtil.KEY_LAST_CARRIERS2)}
            2 -> {SharedPreferencesUtil.setCarriers(context, carriers, SharedPreferencesUtil.KEY_LAST_CARRIERS3)}
        }
    }


    fun reset() {
        carrierList1 = listOf()
        carrierList2 = listOf()
        carrierList3 = listOf()
        Scopes.getMainCoroutine().launch {
            carrierList.value = listOf()
        }
    }
}

data class Carrier (
    val index : Int,
    var type : StardustTypeFunctionality,
    val name : String,
    var f : StardustConfigurationParser.StardustCarrier? = null,
    private var presetActiveFunctionality: Set<FunctionalityType>? = null
) {

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

        return (index == other.index) &&
                (type == other.type) &&
                (name == other.name) // Ignore functionalityTypeList
    }

    override fun hashCode(): Int {
        return index.hashCode() * 31 +
                type.hashCode() * 31 +
                name.hashCode()
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


