package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.util.Carrier
import kotlin.collections.reversedArray

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

    }

    enum class CurrentPreset (val value : Int){
        PRESET1(0),
        PRESET2(1),
        PRESET3(2);

        companion object {

            fun fromValue(value: Int): CurrentPreset? {
                return CurrentPreset.entries.find { it.value == value }
            }
        }
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

    enum class CarrierType(val type: Int, val typeName: String) {
        HR(0, "High Rate"),
        LR(1, "Low Rate"),
        ST(2, "Fast Rate");

        fun getAllowedFunctionalityOptions(): Set<FunctionalityType> {
            return when (this) {
                HR -> HR_ALLOWED
                LR -> LR_ALLOWED
                ST -> ST_ALLOWED
            }
        }

        companion object {

            private val HR_ALLOWED = setOf(
                FunctionalityType.TEXT,
                FunctionalityType.LOCATION,
                FunctionalityType.PTT,
                FunctionalityType.BFT,
                FunctionalityType.FILE,
                FunctionalityType.IMAGE,
                FunctionalityType.REPORTS,
                FunctionalityType.ACK,
                FunctionalityType.SOS,
            )

            private val LR_ALLOWED = setOf(
                FunctionalityType.TEXT,
                FunctionalityType.LOCATION,
                FunctionalityType.REPORTS,
                FunctionalityType.ACK,
                FunctionalityType.SOS
            )

            private val ST_ALLOWED = setOf(
                FunctionalityType.IMAGE,
                FunctionalityType.FILE
            )

            fun fromType(type: Int): CarrierType =
                entries.firstOrNull { it.type == type } ?: HR
        }
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
                    licenseType = byteArrayToInt(licenceNumberBytes).let { licenceNumber ->
                        LicenseType.entries.find { it.type == licenceNumber } ?: LicenseType.UNDEFINED },
                    presets = presets,
                    powerLOTX = byteArrayToInt(LOTXPowerBytes.reversedArray()),
                    powerLORX = byteArrayToInt(LORXPowerBytes.reversedArray()),
                    frequencyLOTX = byteArrayToUInt32(LOTXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyLORX = byteArrayToUInt32(LORXFreqBytes.reversedArray()).toDouble().div(MHz),
                    currentPreset = CurrentPreset.entries[byteArrayToInt(currentPresetBytes)],
                    bittelType = StardustType.entries[byteArrayToInt(bittelType)],
                    portType = getPortType(byteArrayToInt(portType)),
                    crcType = byteArrayToInt(crcType),
                    serverByteType = byteArrayToInt(serverByteType),
                    debugIgnoreCanTransmit = byteArrayToBoolean(debugCanTrasmit),
                    snifferMode = SnifferMode.entries[byteArrayToInt(snifferModeBytes)],
                    appId = appIdBytes.reversedArray().toHex().substring(0,8),
                    stardustId = bittelIdBytes.reversedArray().toHex().substring(0,8),
                    sosDestinations = parseSosDestinations(SOSDataBytes),
                    deviceModel = deviceModelBytes.toString(Charsets.UTF_8),
                    deviceSerial = deviceSerialBytes.toString(Charsets.UTF_8),
                    antenna = AntennaType.entries[byteArrayToInt(antennaBytes)],
                    radioLODeduction = byteArrayToFloat(radioLODeduction.reversedArray()),
                    power12V = byteArrayToFloat(power12V.reversedArray()),
                    powerBattery = byteArrayToFloat(powerBattery.reversedArray()),
                    batteryChargeStatus = StardustBatteryCharge.values()[byteArrayToInt(batteryChargeStatus)],
                    mcuTemperature = byteArrayToInt(mcuTemperature),
                    rdpLevel = StardustRDPLevel.entries[byteArrayToInt(rdpLevel)],
                )
                return bittelConfigurationPackage
            }catch (e : Exception) {
                e.printStackTrace()
            }

        }
        return null
    }

    private fun parseSosDestinations(bytes: ByteArray): List<String> {
        val headerSize = 1
        val idSize = 4
        val expectedSize = headerSize + idSize * 2

        if (bytes.size < expectedSize) return emptyList()

        fun extractId(offset: Int): String =
            bytes.copyOfRange(offset, offset + idSize)
                .reversedArray()
                .toHex()
                .take(idSize * 2) // 8 hex chars, safe

        return listOf(
            extractId(headerSize),
            extractId(headerSize + idSize)
        )
    }


    private fun parsePresets (byteArray: ByteArray) : List<Preset>{
        val presetList : MutableList<Preset> = mutableListOf()
        var offset = 0
        try {
            val preset1Bytes = cutByteArray(byteArray, presetParseLength, offset)
            val preset1 = parsePreset(preset1Bytes, 0)
            preset1.currentPreset = CurrentPreset.PRESET1
            presetList.add(preset1)
            offset += presetParseLength
            val preset2Bytes = cutByteArray(byteArray, presetParseLength, offset)
            val preset2 = parsePreset(preset2Bytes, 1)
            preset2.currentPreset = CurrentPreset.PRESET2
            presetList.add(preset2)
            offset += presetParseLength
            val preset3Bytes = cutByteArray(byteArray, presetParseLength, offset)
            val preset3 = parsePreset(preset3Bytes, 2)
            preset3.currentPreset = CurrentPreset.PRESET3
            presetList.add(preset3)
        } catch (e : Exception) {
            e.printStackTrace()
        }
        return presetList
    }

    private fun parsePreset (byteArray: ByteArray, i: Int) : Preset {
        val preset = Preset(index = i)

        try {
            var offset = 0
            for(i in 0..3) {
                val bytes = cutByteArray(byteArray, xcvrParseLength, offset)
                parseXcvr(bytes, i)?.let { preset.xcvrList.add(it) }
                offset += xcvrParseLength
            }
        } catch (e : Exception) {
            e.printStackTrace()
        }
        return preset
    }

    private fun parseXcvr(byteArray: ByteArray, xcvrNum : Int = 0): xcvr? {
        try {
            var offset = 0

            val txFrequency = cutByteArray(byteArray, xcvrParseTXLength, offset)
                .reversedArray()
                .let { byteArrayToUInt32(it).toDouble().div(MHz) }
            offset += xcvrParseTXLength

            val rxFrequency = cutByteArray(byteArray, xcvrParseRXLength, offset)
                .reversedArray()
                .let { byteArrayToUInt32(it).toDouble().div(MHz) }
            offset += xcvrParseRXLength

            val power = cutByteArray(byteArray, xcvrParsePowerLength, offset)
                .reversedArray()
                .let { byteArrayToInt(it) }
            offset += xcvrParsePowerLength

            val byteFunctionality = cutByteArray(byteArray, xcvrParseFunctionalityLength, offset)[0].toInt() and 0xFF  // Ensure unsigned byte interpretation
            // Remaining bits (bits 1–7) → options
            val options = byteFunctionality shr 1
            offset += xcvrParseFunctionalityLength

            val xcvrCarrierBytes = cutByteArray(byteArray, xcvrParseTransceiverLength, offset)
            val byteCarrier = xcvrCarrierBytes[0].toInt() and 0xFF  // Ensure unsigned byte interpretation
            val carrierBits = byteCarrier and 0b00000011 // Mask bits 0 and 1

            val carrierIndex = carrierBits
            val carrierType = when {
                xcvrNum == 3 -> CarrierType.ST
                (byteFunctionality and 0b00000001) == 0 -> CarrierType.HR
                else -> CarrierType.LR
            }


            // Extract third bit (bit 2) for carrierOn
            val carrierOn = (byteCarrier and 0b00000100) != 0
            return xcvr(
                txFrequency = txFrequency,
                rxFrequency = rxFrequency,
                power = power,
                options = options,
                carrierOn = carrierOn,
                carrier = Carrier(
                    index = carrierIndex,
                    type = carrierType
                ))
        } catch (e : Exception) {
            e.printStackTrace()
            return null
        }
    }

    data class xcvr (
        var txFrequency: Double,
        var rxFrequency: Double,
        var power: Int,
        var options: Int,
        var carrier: Carrier,
        var carrierOn: Boolean
    ) {
        fun getOptions() : Set<FunctionalityType> {
            return FunctionalityType.entries.filter { type -> (options and type.bitwise) == type.bitwise }.toSet()
        }

        fun hasDefaultFrequency(): Boolean {
            return txFrequency == 0.0 && rxFrequency == 0.0
        }
    }

    data class Preset (
        val index : Int,
        var currentPreset: CurrentPreset? = null,
        val xcvrList: MutableList<xcvr> = mutableListOf()
    )

    private fun getPortType (portType : Int): PortType {
        return PortType.entries.find { it.type == portType } ?: PortType.UNDEFINED
    }

}