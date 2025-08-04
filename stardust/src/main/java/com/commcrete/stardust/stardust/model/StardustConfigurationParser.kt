package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.FunctionalityType

class StardustConfigurationParser : StardustParser() {

    companion object{
        const val sizeLength = 1

        const val xcvrParseLength = 11
        const val presetParseLength = 44
        const val presetParsetsLength = 132
        const val xcvrParseTXLength = 4
        const val xcvrParseRXLength = 4
        const val xcvrParsePowerLength = 1
        const val xcvrParseFunctionalityLength = 1
        const val xcvrParseTransceiverLength = 1


        const val LOTXFreqLength = 4
        const val LORXFreqLength = 4
        const val LOTXPowerLength = 1
        const val LORXPowerLength = 1

        const val currentPresetLength = 1

        const val bittelTypeLength = 1
        const val portTypeLength = 1
        const val crcTypeLength = 1
        const val serverByteTypeLength = 1
        const val debuIgnoreCanTrasmitLength = 1
        const val snifferModeLength = 1
        const val bittelAddressLength = 4
        const val appAddressLength = 4
        const val logModeLength = 2
        const val antennaTypeLength = 1
        const val tOutLength = 1
        const val SOSDataLength = 9
        const val radioLODeductionLength = 4
        const val radioXcvr4DeductionLength = 4
        const val deviceModelLength = 14
        const val deviceSerialLength = 14
        const val licenceNumberLength = 1
        const val transmitterModeLength = 1
        const val power12VLength = 4
        const val powerBatteryLength = 4
        const val batteryChargeStatusLength = 1
        const val mcuTemperatureLength = 1
        const val rdpLevelLength = 1
        const val deviceTypeLength = 1
        const val MHz = 1000000

//
//        const val frequencyXcvr1SatelliteTXBytesLength = 4
//        const val frequencyXcvr1RadioTXBytesLength = 4
//        const val frequencyXcvr2SatelliteTXBytesLength = 4
//        const val frequencyXcvr1SatelliteRXBytesLength = 4
//        const val frequencyXcvr1RadioRXBytesLength = 4
//        const val frequencyXcvr2SatelliteRXBytesLength = 4
//        const val powerLOTXLength = 1
//        const val powerLORXLength = 1
//
//        const val powerXcvr1TXLength = 1
//        const val powerXcvr2TXLength = 1
//        const val redundent = 1
//        const val frequencyXcvr3SatelliteTXBytesLength = 4
//        const val frequencyXcvr4SatelliteTXBytesLength = 4
//        const val frequencyXcvr3SatelliteRXBytesLength = 4
//        const val frequencyXcvr4SatelliteRXBytesLength = 4
//        const val powerXcvr3TXLength = 1
//        const val powerXcvr4TXLength = 1
//        const val functionXcvr1Length = 1
//        const val functionXcvr2Length = 1
//        const val functionXcvr3Length = 1
//        const val frequencyLOTXLength = 4
//        const val frequencyLORXLength = 4
//        const val frequencyXcvr2TXLength = 4
//        const val frequencyXcvr2RXLength = 4
//
//        const val frequencyXcvr3TXLength = 4
//        const val frequencyXcvr3RXLength = 4
//        const val frequencyXcvr4TXLength = 4
//        const val frequencyXcvr4RXLength = 4


    }

    enum class CurrentPreset (val value : Int){
        PRESET1(0),
        PRESET2(1),
        PRESET3(2),
    }

    enum class StardustType (val type : Int){
        HANDHELD(0),
        VEHICLE(1),
        HANDHELD_VEHICLE(2),
        SERVER_VEHICLE(3),
    }

    enum class PortType (val type : Int){
        UNDEFINED(-1),
        BLUETOOTH_DISABLED_BLE(0),
        BLUETOOTH_DISABLED_USB(1),
        BLUETOOTH_ENABLED_BLE(2),
        BLUETOOTH_ENABLED_USB(3),
    }

    enum class StardustBatteryCharge (val type : Int){
        NON_RECOVERABLE_FAULT(0),
        RECOVERABLE_FAULT(1),
        CHARGE_IN_PROGRESS(2),
        CHARGE_COMPLETED(3),
    }

    enum class StardustRDPLevel (val type : Int){
        MCU_READING_ENABLE(0),
        MCU_READING_DISABLE(1),
        MCU_READING_ERROR(2),
    }

    enum class SnifferMode (val type : Int){
        DEFAULT(0),
        FUTURE_USE(1),
        ALL(2),
    }

    enum class AntennaType (val type : Int){
        PASSIVE(0),
        ACTIVE(1),
    }

    enum class StardustTypeFunctionality (val type : Int){
        HR(0),
        LR(1),
        ST(2)
    }


    enum class StardustCarrier (val carrier : Int, val carrierName : String){
        Carrier1(0, "Carrier 1"),
        Carrier2(1, "Carrier 2"),
        Carrier3(2, "Carrier 3")
    }

    fun parseConfiguration(StardustPackage: StardustPackage) : StardustConfigurationPackage? {
        StardustPackage.data?.let { intArray ->
            try {

                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 0
                val sizeBytes = cutByteArray(byteArray, sizeLength, offset)
                offset += sizeLength
                //Preset 1
//Preset 1
                val presetsBytes = cutByteArray(byteArray, presetParsetsLength, offset)
                val presets = parsePresets(presetsBytes)
                offset += presetParsetsLength

                val LOTXFreqBytes = cutByteArray(byteArray, LOTXFreqLength, offset)
                offset += LOTXFreqLength
                val LORXFreqBytes = cutByteArray(byteArray, LORXFreqLength, offset)
                offset += LORXFreqLength
                val LOTXPowerBytes = cutByteArray(byteArray, LOTXPowerLength, offset)
                offset += LOTXPowerLength
                val LORXPowerBytes = cutByteArray(byteArray, LORXPowerLength, offset)
                offset += LORXPowerLength

                val currentPresetBytes = cutByteArray(byteArray, currentPresetLength, offset)
                offset += currentPresetLength

                val bittelType = cutByteArray(byteArray, bittelTypeLength, offset)
                offset += bittelTypeLength
                val portType = cutByteArray(byteArray, portTypeLength, offset)
                offset += portTypeLength
                val crcType = cutByteArray(byteArray, crcTypeLength, offset)
                offset += crcTypeLength
                val serverByteType = cutByteArray(byteArray, serverByteTypeLength, offset)
                offset += serverByteTypeLength
                val debugCanTrasmit = cutByteArray(byteArray, debuIgnoreCanTrasmitLength, offset)
                offset += debuIgnoreCanTrasmitLength
                val snifferModeBytes = cutByteArray(byteArray, snifferModeLength, offset)
                offset += snifferModeLength
                val bittelIdBytes = cutByteArray(byteArray, bittelAddressLength, offset)
                offset += bittelAddressLength
                val appIdBytes = cutByteArray(byteArray, appAddressLength, offset)
                offset += appAddressLength
                val logBytes = cutByteArray(byteArray, logModeLength, offset)
                offset += logModeLength
                val antennaBytes = cutByteArray(byteArray, antennaTypeLength, offset)
                offset += antennaTypeLength
                val tOutBytes = cutByteArray(byteArray, tOutLength, offset)
                offset += tOutLength
                val SOSDataBytes = cutByteArray(byteArray, SOSDataLength, offset)
                offset += SOSDataLength
                val radioLODeduction = cutByteArray(byteArray, radioLODeductionLength, offset)
                offset += radioLODeductionLength
                val radioXcvr4DeductionBytes = cutByteArray(byteArray, radioXcvr4DeductionLength, offset)
                offset += radioXcvr4DeductionLength
                val deviceModelBytes = cutByteArray(byteArray, deviceModelLength, offset)
                offset += deviceModelLength
                val deviceSerialBytes = cutByteArray(byteArray, deviceSerialLength, offset)
                offset += deviceSerialLength
                val licenceNumberBytes = cutByteArray(byteArray, licenceNumberLength, offset)
                offset += licenceNumberLength
                val transmitterModeBytes = cutByteArray(byteArray, transmitterModeLength, offset)
                offset += transmitterModeLength
                val power12V = cutByteArray(byteArray, power12VLength, offset)
                offset += power12VLength
                val powerBattery = cutByteArray(byteArray, powerBatteryLength, offset)
                offset += powerBatteryLength
                val batteryChargeStatus = cutByteArray(byteArray, batteryChargeStatusLength, offset)
                offset += batteryChargeStatusLength
                val mcuTemperature = cutByteArray(byteArray, mcuTemperatureLength, offset)
                offset += mcuTemperatureLength
                val rdpLevel = cutByteArray(byteArray, rdpLevelLength, offset)
                offset += rdpLevelLength
                val deviceTypeBytes = cutByteArray(byteArray, deviceTypeLength, offset)
                offset += deviceTypeLength

                val bittelConfigurationPackage = StardustConfigurationPackage(
                    presets = presets,
                    powerLOTX = byteArrayToInt(LOTXPowerBytes.reversedArray()),
                    powerLORX = byteArrayToInt(LORXPowerBytes.reversedArray()),
                    frequencyLOTX = byteArrayToUInt32(LOTXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyLORX = byteArrayToUInt32(LORXFreqBytes.reversedArray()).toDouble().div(MHz),

                    currentPreset = CurrentPreset.values()[byteArrayToInt(currentPresetBytes)],

                    bittelType = StardustType.values()[byteArrayToInt(bittelType)],
                    portType = getPortType(byteArrayToInt(portType)),
                    crcType = byteArrayToInt(crcType),
                    serverByteType = byteArrayToInt(serverByteType),
                    debugIgnoreCanTransmit = byteArrayToBoolean(debugCanTrasmit),
                    snifferMode = SnifferMode.values()[byteArrayToInt(snifferModeBytes)],
                    appId = appIdBytes.reversedArray().toHex().substring(0,8),
                    stardustId = bittelIdBytes.reversedArray().toHex().substring(0,8),
                    antenna = AntennaType.values()[byteArrayToInt(antennaBytes)],
                    radioLODeduction = byteArrayToFloat(radioLODeduction.reversedArray()),
                    power12V = byteArrayToFloat(power12V.reversedArray()),
                    powerBattery = byteArrayToFloat(powerBattery.reversedArray()),
                    batteryChargeStatus = StardustBatteryCharge.values()[byteArrayToInt(batteryChargeStatus)],
                    mcuTemperature = byteArrayToInt(mcuTemperature),
                    rdpLevel = StardustRDPLevel.values()[byteArrayToInt(rdpLevel)],
                )
                return bittelConfigurationPackage
            }catch (e : Exception) {
                e.printStackTrace()
            }

        }
        return null
    }


    private fun parsePresets (byteArray: ByteArray) : List<Preset>{
        val presetList : MutableList<Preset> = mutableListOf()
        var offset = 0
        try {
            val preset1Bytes = cutByteArray(byteArray, presetParseLength, offset)
            val preset1 = parsePreset(preset1Bytes)
            presetList.add(preset1)
            offset += presetParseLength
            val preset2Bytes = cutByteArray(byteArray, presetParseLength, offset)
            val preset2 = parsePreset(preset2Bytes)
            presetList.add(preset2)
            offset += presetParseLength
            val preset3Bytes = cutByteArray(byteArray, presetParseLength, offset)
            val preset3 = parsePreset(preset3Bytes)
            presetList.add(preset3)
            offset += presetParseLength
        } catch (e : Exception) {
            e.printStackTrace()
        }
        return presetList
    }

    private fun parsePreset (byteArray: ByteArray) : Preset {
        val preset = Preset()
        var offset = 0

        try {
            val xcvr1Bytes = cutByteArray(byteArray, xcvrParseLength, offset)
            val xcvr1 = parseXcvr(xcvr1Bytes, 0)
            preset.xcvrList.add(xcvr1)
            offset += xcvrParseLength
            val xcvr2Bytes = cutByteArray(byteArray, xcvrParseLength, offset)
            val xcvr2 = parseXcvr(xcvr2Bytes, 1)
            preset.xcvrList.add(xcvr2)
            offset += xcvrParseLength
            val xcvr3Bytes = cutByteArray(byteArray, xcvrParseLength, offset)
            val xcvr3 = parseXcvr(xcvr3Bytes, 2)
            preset.xcvrList.add(xcvr3)
            offset += xcvrParseLength
            val xcvr4Bytes = cutByteArray(byteArray, xcvrParseLength, offset)
            val xcvr4 = parseXcvr(xcvr4Bytes, 3)
            preset.xcvrList.add(xcvr4)
            offset += xcvrParseLength
        } catch (e : Exception) {
            e.printStackTrace()
        }
        return preset
    }

    private fun parseXcvr (byteArray: ByteArray, xcvrNum : Int = 0) : xcvr {
        val xcvr = xcvr()
        var offset = 0
        try {
            val xcvrTxFrequencyBytes = cutByteArray(byteArray, xcvrParseTXLength, offset)
            xcvr.txFrequency = byteArrayToUInt32(xcvrTxFrequencyBytes.reversedArray()).toDouble().div(MHz)
            offset += xcvrParseTXLength

            val xcvrRxFrequencyBytes = cutByteArray(byteArray, xcvrParseRXLength, offset)
            xcvr.rxFrequency = byteArrayToUInt32(xcvrRxFrequencyBytes.reversedArray()).toDouble().div(MHz)
            offset += xcvrParseRXLength

            val xcvrPowerBytes = cutByteArray(byteArray, xcvrParsePowerLength, offset)
            xcvr.power = byteArrayToInt(xcvrPowerBytes.reversedArray())
            offset += xcvrParsePowerLength

            val xcvrFunctionalityBytes = cutByteArray(byteArray, xcvrParseFunctionalityLength, offset)
            val byteFunctionality = xcvrFunctionalityBytes[0].toInt() and 0xFF  // Ensure unsigned byte interpretation
            xcvr.functionality = if ((byteFunctionality and 0b00000001) == 0) {
                StardustTypeFunctionality.HR
            } else {
                StardustTypeFunctionality.LR
            }
            if(xcvrNum == 3) {
                xcvr.functionality = StardustTypeFunctionality.ST
            }

            // Remaining bits (bits 1–7) → options
            xcvr.options = byteFunctionality shr 1
            offset += xcvrParseFunctionalityLength

            val xcvrCarrierBytes = cutByteArray(byteArray, xcvrParseTransceiverLength, offset)
            val byteCarrier = xcvrCarrierBytes[0].toInt() and 0xFF  // Ensure unsigned byte interpretation
            val carrierBits = byteCarrier and 0b00000011 // Mask bits 0 and 1

            // Map carrier bits to enum
            xcvr.carrier = when (carrierBits) {
                0 -> StardustCarrier.Carrier1
                1 -> StardustCarrier.Carrier2
                2 -> StardustCarrier.Carrier3
                else -> StardustCarrier.Carrier1 // fallback/default
            }

            // Extract third bit (bit 2) for carrierOn
            xcvr.carrierOn = (byteCarrier and 0b00000100) != 0

        }catch (e : Exception) {
            e.printStackTrace()
        }
        return xcvr
    }

    data class xcvr (
        var txFrequency: Double = 0.0,
        var rxFrequency: Double = 0.0,
        var power: Int = 0,
        var functionality : StardustTypeFunctionality = StardustTypeFunctionality.HR,
        var options : Int = 0,
        var carrier : StardustCarrier = StardustCarrier.Carrier1,
        var carrierOn : Boolean = true
    ) {
        private fun getOptions () : Set<FunctionalityType>{
            val functionalityOptions = mutableSetOf<FunctionalityType>()
            for (type in FunctionalityType.values()) {
                if ((options and type.bitwise) == type.bitwise) {
                    functionalityOptions.add(type)
                }
            }
            return functionalityOptions
        }

        fun getRadio (carrier: Carrier) : Carrier {
            carrier.functionalityTypeList = getOptions().toMutableSet()
            return carrier
        }
    }

    data class Preset (
        val xcvrList: MutableList<xcvr> = mutableListOf()
    )

    private fun getPortType (portType : Int): PortType {
        PortType.values().iterator().forEach {
            if(it.type == portType) return it
        }
        return PortType.UNDEFINED
    }
}