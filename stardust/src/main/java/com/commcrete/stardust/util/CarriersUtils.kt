package com.commcrete.stardust.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage

object CarriersUtils {

    val carrierList : MutableLiveData<List<Carrier>> = MutableLiveData()

    fun getCarrierList(bittelConfigurationPackage: StardustConfigurationPackage) : List<Carrier> {
        val mutableList : MutableList<Carrier> = arrayListOf()
        val radios = bittelConfigurationPackage.getCurrentRadios()
        mutableList.add(Carrier(0, radios.xcvr1,  "RD1"))
        mutableList.add(Carrier(1, radios.xcvr2,  "RD2"))
        mutableList.add(Carrier(2, radios.xcvr3,  "RD3"))
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
        val list = getCarrierList(bittelConfigurationPackage)
        updateCarrierList(list.toMutableList())
        return list
    }

    fun setLocalCarrierList () : List<Carrier>?{
        val mutableList = SharedPreferencesUtil.getCarriers(DataManager.context)
        mutableList?.let { carrierList.value = it }
        return carrierList.value
    }

    private fun updateCarrierList (mutableList : MutableList<Carrier>) {
        SharedPreferencesUtil.setCarriers(DataManager.context, mutableList)
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
        val savedList = SharedPreferencesUtil.getCarriers(DataManager.context)
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

    fun getDefaults () {
        val mutableList = SharedPreferencesUtil.getCarriers(DataManager.context)?.toMutableList()
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
                    }
                }
                StardustConfigurationParser.StardustTypeFunctionality.ST -> {
                    it.functionalityTypeList.clear()
                    it.functionalityTypeList.add(FunctionalityType.FILE)
                    it.functionalityTypeList.add(FunctionalityType.IMAGE)
                }
            }
        }
        mutableList?.let { updateCarrierList(it) }
    }
}

enum class FunctionalityType {
    SOS,
    TEXT,
    LOCATION,
    PTT,
    BFT,
    FILE,
    IMAGE
}

data class Carrier (
    val index : Int,
    val type : StardustConfigurationParser.StardustTypeFunctionality,
    val name : String,
    val functionalityTypeList : MutableSet<FunctionalityType> = mutableSetOf()
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
