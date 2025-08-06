package com.commcrete.stardust.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.launch

object CarriersUtils {

    val carrierList : MutableLiveData<List<Carrier>> = MutableLiveData()

    fun getCarrierList(bittelConfigurationPackage: StardustConfigurationPackage) : List<Carrier> {
        val mutableList : MutableList<Carrier> = arrayListOf()
        val radios = bittelConfigurationPackage.getCurrentRadios()
        val preset = ConfigurationUtils.selectedPreset
        val defaults1 = preset?.xcvrList?.get(0)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        val defaults2 = preset?.xcvrList?.get(1)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        val defaults3 = preset?.xcvrList?.get(2)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        mutableList.add(Carrier(0, radios.xcvr1,  "RD1", preset?.xcvrList?.get(0)?.carrier,
            functionalityTypeList = defaults1))
        mutableList.add(Carrier(1, radios.xcvr2,  "RD2", preset?.xcvrList?.get(1)?.carrier,
            functionalityTypeList = defaults2))
        mutableList.add(Carrier(2, radios.xcvr3,  "RD3", preset?.xcvrList?.get(2)?.carrier,
            functionalityTypeList = defaults3))
        mutableList.add(Carrier(3, StardustConfigurationParser.StardustTypeFunctionality.ST,  "RD4"))
        return mutableList
    }

    fun getCarrierLisByPreset(bittelConfigurationPackage: StardustConfigurationPackage, preset: StardustConfigurationParser.Preset?) : List<Carrier> {
        val mutableList : MutableList<Carrier> = arrayListOf()
        val radios = bittelConfigurationPackage.getCurrentRadios()
        val defaults1 = preset?.xcvrList?.get(0)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        val defaults2 = preset?.xcvrList?.get(1)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        val defaults3 = preset?.xcvrList?.get(2)?.getOptions ()?.toMutableSet() ?: mutableSetOf()
        mutableList.add(Carrier(0, radios.xcvr1,  "RD1", preset?.xcvrList?.get(0)?.carrier,
            functionalityTypeList = defaults1))
        mutableList.add(Carrier(1, radios.xcvr2,  "RD2", preset?.xcvrList?.get(1)?.carrier,
            functionalityTypeList = defaults2))
        mutableList.add(Carrier(2, radios.xcvr3,  "RD3", preset?.xcvrList?.get(2)?.carrier,
            functionalityTypeList = defaults3))
        mutableList.add(Carrier(3, StardustConfigurationParser.StardustTypeFunctionality.ST,  "RD4"))
        return mutableList
    }

    fun getCarrierByControl (deliveryType: StardustControlByte.StardustDeliveryType) : Carrier?{
        when (deliveryType) {
            StardustControlByte.StardustDeliveryType.RD1 -> return carrierList.value?.get(0)
            StardustControlByte.StardustDeliveryType.RD2 -> return carrierList.value?.get(1)
            StardustControlByte.StardustDeliveryType.RD3 -> return carrierList.value?.get(2)
            StardustControlByte.StardustDeliveryType.RD4 -> return carrierList.value?.get(3)
        }
    }

    fun getCarrierListAndUpdate (bittelConfigurationPackage: StardustConfigurationPackage) : List<Carrier> {
        val currentPreset = ConfigurationUtils.currentPreset?.value ?: 0
        for (preset in bittelConfigurationPackage.presets) {
            if(preset.index != currentPreset) {
                updateByPreset(preset, bittelConfigurationPackage)
            }
        }
        val oldList = getLocalCarriersByPreset((currentPreset), DataManager.context)
        if(!oldList.isNullOrEmpty() ) {
            updateCarrierList(oldList.toMutableList())
            return oldList
        }
        val list = getCarrierList(bittelConfigurationPackage)
        updateCarrierList(list.toMutableList())
        return list
    }

    private fun updateByPreset (preset: StardustConfigurationParser.Preset, bittelConfigurationPackage: StardustConfigurationPackage) : List<Carrier> {
        val oldList = getLocalCarriersByPreset((preset.index), DataManager.context)
        if(!oldList.isNullOrEmpty() ) {
            return oldList
        }
        val list = getCarrierLisByPreset(bittelConfigurationPackage, preset)
        setLocalCarriersByPreset((preset.index), list, DataManager.context)
        return list
    }

    fun setLocalCarrierList () : List<Carrier>?{
        val mutableList = getLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), DataManager.context)
        Scopes.getMainCoroutine().launch {
            mutableList?.let { carrierList.value = it }
        }
        return carrierList.value
    }

    private fun updateCarrierList (mutableList : MutableList<Carrier>) {
        setLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), mutableList, DataManager.context)
        carrierList.value = mutableList
    }

    fun updateFunctionalityToCarrier (functionalityType: FunctionalityType, carrier: Carrier) {
        val carriers = carrierList.value
        carriers?.let {
            it.forEach {
                if(it != carrier) {
                    it.functionalityTypeList.remove(functionalityType)
                } else {
                    it.functionalityTypeList.add(functionalityType)
                }
            }
            updateCarrierList(it.toMutableList())
        }
    }

    fun isCarriersChanged (bittelConfigurationPackage: StardustConfigurationPackage) : Boolean {
        val list = getCarrierList(bittelConfigurationPackage)
        val savedList = getLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), DataManager.context)
        return  savedList == null || (list != savedList)
    }

    fun showCarriersChanged(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setMessage("Carriers changed, returning to defaults")
            .setCancelable(true) // Allows dismissing the dialog when clicking outside
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss() // Dismiss the dialog when "Close" is clicked
            }

        val alertDialog = builder.create()
        alertDialog.show()
    }

    fun getRadioToSend (carrier: Carrier? = null, functionalityType: FunctionalityType) :
            Pair<Carrier?, StardustControlByte.StardustDeliveryType> {
        var selectedCarrier = carrier ?: getCarrierByFunctionalityType(functionalityType)
        val deliveryType = when (selectedCarrier?.index) {
            0 -> StardustControlByte.StardustDeliveryType.RD1
            1 -> StardustControlByte.StardustDeliveryType.RD2
            2 -> StardustControlByte.StardustDeliveryType.RD3
            3 -> StardustControlByte.StardustDeliveryType.RD4
            else -> StardustControlByte.StardustDeliveryType.RD1 // Default case
        }
        when (functionalityType) {
            FunctionalityType.SOS -> {
                if (carrier?.type == StardustConfigurationParser.StardustTypeFunctionality.ST) {
                    return getDefaultRadio(functionalityType)
                }
            }
            FunctionalityType.TEXT -> {
                if (carrier?.type == StardustConfigurationParser.StardustTypeFunctionality.ST) {
                    return getDefaultRadio(functionalityType)
                }
            }
            FunctionalityType.LOCATION -> {
                if (carrier?.type == StardustConfigurationParser.StardustTypeFunctionality.ST) {
                    return getDefaultRadio(functionalityType)
                }
            }
            FunctionalityType.PTT -> {
                if (carrier?.type != StardustConfigurationParser.StardustTypeFunctionality.HR) {
                    return getDefaultRadio(functionalityType)
                }
            }
            FunctionalityType.BFT -> {
                if (carrier?.type != StardustConfigurationParser.StardustTypeFunctionality.HR) {
                    return getDefaultRadio(functionalityType)
                }
            }
            FunctionalityType.FILE -> {
                if (carrier?.type == StardustConfigurationParser.StardustTypeFunctionality.LR) {
                    return getDefaultRadio(functionalityType)
                }
            }
            FunctionalityType.IMAGE -> {
                if (carrier?.type == StardustConfigurationParser.StardustTypeFunctionality.LR) {
                    return getDefaultRadio(functionalityType)
                }
            }
        }
        return Pair(selectedCarrier, deliveryType)
    }

    private fun getDefaultRadio ( functionalityType: FunctionalityType) :
            Pair<Carrier?, StardustControlByte.StardustDeliveryType> {
        var selectedCarrier = getCarrierByFunctionalityType(functionalityType)
        val deliveryType = when (selectedCarrier?.index) {
            0 -> StardustControlByte.StardustDeliveryType.RD1
            1 -> StardustControlByte.StardustDeliveryType.RD2
            2 -> StardustControlByte.StardustDeliveryType.RD3
            3 -> StardustControlByte.StardustDeliveryType.RD4
            else -> StardustControlByte.StardustDeliveryType.RD1 // Default case
        }
        return Pair(selectedCarrier, deliveryType)
    }

    private fun getCarrierByFunctionalityType (functionalityType: FunctionalityType) : Carrier? {
        carrierList.value?.forEach {
            if(it.functionalityTypeList.contains(functionalityType)) {
                return it
            }
        }
        return null
    }

    fun getCarrierByStardustPackage (stardustPackage: StardustPackage) : Carrier? {
        when (stardustPackage.stardustControlByte.stardustDeliveryType) {
            StardustControlByte.StardustDeliveryType.RD1 -> {
                return carrierList.value?.get(0)
            }
            StardustControlByte.StardustDeliveryType.RD2 -> {
                return carrierList.value?.get(1)
            }
            StardustControlByte.StardustDeliveryType.RD3 -> {
                return carrierList.value?.get(2)
            }
            StardustControlByte.StardustDeliveryType.RD4 -> {
                return carrierList.value?.get(3)
            }
        }
        return null
    }

    fun getDefaultsFromPresets(config: StardustConfigurationPackage) {
        val mutableList = getLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), DataManager.context)
        val newList = mutableListOf<Carrier>()
        val xcvrList = ConfigurationUtils.selectedPreset?.xcvrList
        mutableList?.forEachIndexed { index, value ->
            xcvrList?.get(index)?.getRadio(value)?.let { newList.add(it) }
        }
        if(newList.isEmpty()) {
            getDefaults()
        } else {
            updateCarrierList(newList)
        }
        val currentPreset = ConfigurationUtils.currentPreset?.value ?: 0
        for (preset in config.presets) {
            if(preset.index != currentPreset) {
                updateByPreset(preset, config)
            }
        }

    }

    fun uploadNewDefaults (config: StardustConfigurationPackage) {
        for (preset in config.presets) {
            val list = getCarrierLisByPreset(config, preset)
            setLocalCarriersByPreset((preset.index), list, DataManager.context)
        }
    }

    fun getDefaults () {
        val mutableList = getLocalCarriersByPreset((ConfigurationUtils.currentPreset?.value ?: 0), DataManager.context)
        var firstHR = false
        mutableList?.forEach {
            when(it.type) {
                StardustConfigurationParser.StardustTypeFunctionality.LR -> {
                    it.functionalityTypeList.clear()
                    it.functionalityTypeList.add(FunctionalityType.SOS)
                }
                StardustConfigurationParser.StardustTypeFunctionality.HR -> {
                    if(!firstHR){
                        it.functionalityTypeList.clear()
                        it.functionalityTypeList.add(FunctionalityType.BFT)
                        firstHR = true
                    } else {
                        it.functionalityTypeList.clear()
                        it.functionalityTypeList.add(FunctionalityType.TEXT)
                        it.functionalityTypeList.add(FunctionalityType.LOCATION)
                        it.functionalityTypeList.add(FunctionalityType.PTT)
                        it.functionalityTypeList.add(FunctionalityType.FILE)
                        it.functionalityTypeList.add(FunctionalityType.IMAGE)
                    }
                }
                StardustConfigurationParser.StardustTypeFunctionality.ST -> {
                    it.functionalityTypeList.clear()
//                    it.functionalityTypeList.add(FunctionalityType.FILE)
//                    it.functionalityTypeList.add(FunctionalityType.IMAGE)
                }
            }
        }
        mutableList?.let { updateCarrierList(it.toMutableList()) }
    }

    private fun getLocalCarriersByPreset (preset : Int, context: Context) : List<Carrier>? {
        val local = when (preset) {
            0 -> {SharedPreferencesUtil.getCarriers1(context)}
            1 -> {SharedPreferencesUtil.getCarriers2(context)}
            2 -> {SharedPreferencesUtil.getCarriers3(context)}
            else -> { null}
        }
        return local
    }

    private fun setLocalCarriersByPreset (preset : Int, carriers: List<Carrier>, context: Context) {
        when (preset) {
            0 -> {SharedPreferencesUtil.setCarriers1(context, carriers)}
            1 -> {SharedPreferencesUtil.setCarriers2(context, carriers)}
            2 -> {SharedPreferencesUtil.setCarriers3(context, carriers)}
        }
    }
}

enum class FunctionalityType (val bitwise : Int) {
    SOS(64),
    TEXT(2),
    LOCATION(4),
    PTT(1),
    BFT(8),
    FILE(16),
    IMAGE(32)
}

data class Carrier (
    val index : Int,
    var type : StardustConfigurationParser.StardustTypeFunctionality,
    val name : String,
    var f : StardustConfigurationParser.StardustCarrier? = null,
    var functionalityTypeList : MutableSet<FunctionalityType> = mutableSetOf()
) {
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
}
