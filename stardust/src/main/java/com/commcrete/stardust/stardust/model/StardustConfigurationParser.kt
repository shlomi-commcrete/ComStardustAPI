package com.commcrete.stardust.stardust.model

class StardustConfigurationParser : StardustParser() {

    companion object{
        const val frequencyHRSatelliteTXBytesLength = 4
        const val frequencyHRRadioTXBytesLength = 4
        const val frequencyLRSatelliteTXBytesLength = 4
        const val frequencyHRSatelliteRXBytesLength = 4
        const val frequencyHRRadioRXBytesLength = 4
        const val frequencyLRSatelliteRXBytesLength = 4
        const val powerLOTXLength = 1
        const val powerLORXLength = 1
        const val powerHRTXLength = 1
        const val powerLRTXLength = 1
        const val radioHRDeductionLength = 4
        const val radioLRDeductionLength = 4
        const val radioLODeductionLength = 4
        const val stardustTypeLength = 1
        const val portTypeLength = 1
        const val crcTypeLength = 1
        const val serverByteTypeLength = 1
        const val debuIgnoreCanTrasmitLength = 1
        const val snifferModeLength = 1
        const val StardustAddressLength = 8
        const val appAddressLength = 8
        const val frequencyLOTXLength = 4
        const val frequencyLORXLength = 4
        const val frequencyLRTXLength = 4
        const val frequencyLRRXLength = 4
        const val powerBatteryLength = 4
        const val power12VLength = 4
        const val batteryChargeStatusLength = 1
        const val mcuTemperatureLength = 1
        const val rdpLevelLength = 1

        const val MHz = 1000000
    }

    enum class StardustType (val type : Int){
        HANDHELD(0),
        VEHICLE(1),
        HANDHELD_VEHICLE(2),
        SERVER_VEHICLE(3),
    }

    enum class PortType (val type : Int){
        UNDEFINED(-1),
        BLUETOOTH(0),
        USB(1),
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

    fun parseConfiguration(StardustPackage: StardustPackage) : StardustConfigurationPackage? {
        StardustPackage.data?.let { intArray ->
            try {
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 0
                val frequencyHRSatelliteTXBytes = cutByteArray(byteArray, frequencyHRSatelliteTXBytesLength, offset)
                offset += frequencyHRSatelliteTXBytesLength
                val frequencyHRRadioTXBytes = cutByteArray(byteArray, frequencyHRRadioTXBytesLength, offset)
                offset += frequencyHRRadioTXBytesLength
                val frequencyLRSatelliteTXBytes = cutByteArray(byteArray, frequencyLRSatelliteTXBytesLength, offset)
                offset += frequencyLRSatelliteTXBytesLength
                val frequencyHRSatelliteRXBytes = cutByteArray(byteArray, frequencyHRSatelliteRXBytesLength, offset)
                offset += frequencyHRSatelliteRXBytesLength
                val frequencyHRRadioRXBytes = cutByteArray(byteArray, frequencyHRRadioRXBytesLength, offset)
                offset += frequencyHRRadioRXBytesLength
                val frequencyLRSatelliteRXBytes = cutByteArray(byteArray, frequencyLRSatelliteRXBytesLength, offset)
                offset += frequencyLRSatelliteRXBytesLength
                val powerLOTX = cutByteArray(byteArray, powerLOTXLength, offset)
                offset += powerLOTXLength
                val powerLORX = cutByteArray(byteArray, powerLORXLength, offset)
                offset += powerLORXLength
                val powerHRTX = cutByteArray(byteArray, powerHRTXLength, offset)
                offset += powerHRTXLength
                val powerLRTX = cutByteArray(byteArray, powerLRTXLength, offset)
                offset += powerLRTXLength
                val radioHRDeduction = cutByteArray(byteArray, radioHRDeductionLength, offset)
                offset += radioHRDeductionLength
                val radioLRDeduction = cutByteArray(byteArray, radioLRDeductionLength, offset)
                offset += radioLRDeductionLength
                val radioLODeduction = cutByteArray(byteArray, radioLODeductionLength, offset)
                offset += radioLODeductionLength
                val stardustType = cutByteArray(byteArray, stardustTypeLength, offset)
                offset += stardustTypeLength
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
                val StardustIdBytes = cutByteArray(byteArray, StardustAddressLength, offset)
                offset += StardustAddressLength
                val appIdBytes = cutByteArray(byteArray, StardustAddressLength, offset)
                offset += StardustAddressLength
                val frequencyLOTX = cutByteArray(byteArray, frequencyLOTXLength, offset)
                offset += frequencyLOTXLength
                val frequencyLORX = cutByteArray(byteArray, frequencyLORXLength, offset)
                offset += frequencyLORXLength
                val frequencyLRTX = cutByteArray(byteArray, frequencyLRTXLength, offset)
                offset += frequencyLRTXLength
                val frequencyLRRX = cutByteArray(byteArray, frequencyLRRXLength, offset)
                offset += frequencyLRRXLength
                val powerBattery = cutByteArray(byteArray, powerBatteryLength, offset)
                offset += powerBatteryLength
                val power12V = cutByteArray(byteArray, power12VLength, offset)
                offset += power12VLength
                val batteryChargeStatus = cutByteArray(byteArray, batteryChargeStatusLength, offset)
                offset += batteryChargeStatusLength
                val mcuTemperature = cutByteArray(byteArray, mcuTemperatureLength, offset)
                offset += mcuTemperatureLength
                val rdpLevel = cutByteArray(byteArray, rdpLevelLength, offset)
                offset += rdpLevelLength
                val StardustConfigurationPackage = StardustConfigurationPackage(
                    frequencyHRSatelliteTXBytes = byteArrayToUInt32(frequencyHRSatelliteTXBytes.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyHRRadioTXBytes = byteArrayToUInt32(frequencyHRRadioTXBytes.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyLRSatelliteTXBytes = byteArrayToUInt32(frequencyLRSatelliteTXBytes.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyHRSatelliteRXBytes = byteArrayToUInt32(frequencyHRSatelliteRXBytes.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyHRRadioRXBytes = byteArrayToUInt32(frequencyHRRadioRXBytes.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyLRSatelliteRXBytes = byteArrayToUInt32(frequencyLRSatelliteRXBytes.reversedArray()).toDouble().div(
                        MHz
                    ),
                    powerLOTX = byteArrayToInt(powerLOTX.reversedArray()),
                    powerLORX = byteArrayToInt(powerLORX.reversedArray()),
                    powerHRTX = byteArrayToInt(powerHRTX.reversedArray()),
                    powerLRTX = byteArrayToInt(powerLRTX.reversedArray()),
                    radioHRDeduction = byteArrayToFloat(radioHRDeduction.reversedArray()),
                    radioLRDeduction = byteArrayToFloat(radioLRDeduction.reversedArray()),
                    radioLODeduction = byteArrayToFloat(radioLODeduction.reversedArray()),
                    stardustType = StardustType.values()[byteArrayToInt(stardustType)],
                    portType = getPortType(byteArrayToInt(portType)),
                    crcType = byteArrayToInt(crcType),
                    serverByteType = byteArrayToInt(serverByteType),
                    frequencyLOTX = byteArrayToUInt32(frequencyLOTX.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyLORX = byteArrayToUInt32(frequencyLORX.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyLRTX = byteArrayToUInt32(frequencyLRTX.reversedArray()).toDouble().div(
                        MHz
                    ),
                    frequencyLRRX = byteArrayToUInt32(frequencyLRRX.reversedArray()).toDouble().div(
                        MHz
                    ),
                    powerBattery = byteArrayToFloat(powerBattery.reversedArray()),
                    power12V = byteArrayToFloat(power12V.reversedArray()),
                    batteryChargeStatus = StardustBatteryCharge.values()[byteArrayToInt(batteryChargeStatus)],
                    mcuTemperature = byteArrayToInt(mcuTemperature),
                    rdpLevel = StardustRDPLevel.values()[byteArrayToInt(rdpLevel)],
                    snifferMode = SnifferMode.values()[byteArrayToInt(snifferModeBytes)],
                    appId = appIdBytes.reversedArray().toHex().substring(8,16),
                    StardustId = StardustIdBytes.reversedArray().toHex().substring(8,16)
                    )
                return StardustConfigurationPackage
            }catch (e : Exception) {
                e.printStackTrace()
            }

        }
        return null
    }

    private fun getPortType (portType : Int): PortType {
        PortType.values().iterator().forEach {
            if(it.type == portType) return it
        }
        return PortType.UNDEFINED
    }
}