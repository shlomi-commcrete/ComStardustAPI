package com.commcrete.stardust.stardust.model

class StardustConfigurationParser : StardustParser() {

    companion object{
        const val sizeLength = 1
        const val frequencyXcvr1SatelliteTXBytesLength = 4
        const val frequencyXcvr1RadioTXBytesLength = 4
        const val frequencyXcvr2SatelliteTXBytesLength = 4
        const val frequencyXcvr1SatelliteRXBytesLength = 4
        const val frequencyXcvr1RadioRXBytesLength = 4
        const val frequencyXcvr2SatelliteRXBytesLength = 4
        const val powerLOTXLength = 1
        const val powerLORXLength = 1
        const val powerXcvr1TXLength = 1
        const val powerXcvr2TXLength = 1
        const val radioLODeductionLength = 4
        const val bittelTypeLength = 1
        const val portTypeLength = 1
        const val crcTypeLength = 1
        const val serverByteTypeLength = 1
        const val debuIgnoreCanTrasmitLength = 1
        const val snifferModeLength = 1
        const val bittelAddressLength = 4
        const val appAddressLength = 4
        const val redundent = 1
        const val logModeLength = 2
        const val antennaTypeLength = 1
        const val tOutLength = 1
        const val frequencyXcvr3SatelliteTXBytesLength = 4
        const val frequencyXcvr4SatelliteTXBytesLength = 4
        const val frequencyXcvr3SatelliteRXBytesLength = 4
        const val frequencyXcvr4SatelliteRXBytesLength = 4
        const val powerXcvr3TXLength = 1
        const val powerXcvr4TXLength = 1
        const val radioXcvr4DeductionLength = 4
        const val functionXcvr1Length = 1
        const val functionXcvr2Length = 1
        const val functionXcvr3Length = 1
        const val transmitterModeLength = 1
        const val frequencyLOTXLength = 4
        const val frequencyLORXLength = 4
        const val frequencyXcvr2TXLength = 4
        const val frequencyXcvr2RXLength = 4
        const val powerBatteryLength = 4
        const val batteryChargeStatusLength = 1
        const val mcuTemperatureLength = 1
        const val rdpLevelLength = 1
        const val frequencyXcvr3TXLength = 4
        const val frequencyXcvr3RXLength = 4
        const val frequencyXcvr4TXLength = 4
        const val frequencyXcvr4RXLength = 4
        const val deviceTypeLength = 1
        const val power12VLength = 4
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

    fun parseConfiguration(StardustPackage: StardustPackage) : StardustConfigurationPackage? {
        StardustPackage.data?.let { intArray ->
            try {
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 0
                val sizeBytes = cutByteArray(byteArray, sizeLength, offset)
                offset += sizeLength
                val frequencyHRSatelliteTXBytes = cutByteArray(byteArray, frequencyXcvr1SatelliteTXBytesLength, offset)
                offset += frequencyXcvr1SatelliteTXBytesLength
                val frequencyHRRadioTXBytes = cutByteArray(byteArray, frequencyXcvr1RadioTXBytesLength, offset)
                offset += frequencyXcvr1RadioTXBytesLength
                val frequencyLRSatelliteTXBytes = cutByteArray(byteArray, frequencyXcvr2SatelliteTXBytesLength, offset)
                offset += frequencyXcvr2SatelliteTXBytesLength
                val frequencyHRSatelliteRXBytes = cutByteArray(byteArray, frequencyXcvr1SatelliteRXBytesLength, offset)
                offset += frequencyXcvr1SatelliteRXBytesLength
                val frequencyHRRadioRXBytes = cutByteArray(byteArray, frequencyXcvr1RadioRXBytesLength, offset)
                offset += frequencyXcvr1RadioRXBytesLength
                val frequencyLRSatelliteRXBytes = cutByteArray(byteArray, frequencyXcvr2SatelliteRXBytesLength, offset)
                offset += frequencyXcvr2SatelliteRXBytesLength
                val powerLOTX = cutByteArray(byteArray, powerLOTXLength, offset)
                offset += powerLOTXLength
                val powerLORX = cutByteArray(byteArray, powerLORXLength, offset)
                offset += powerLORXLength
                val powerHRTX = cutByteArray(byteArray, powerXcvr1TXLength, offset)
                offset += powerXcvr1TXLength
                val powerLRTX = cutByteArray(byteArray, powerXcvr2TXLength, offset)
                offset += powerXcvr2TXLength
                val radioLODeduction = cutByteArray(byteArray, radioLODeductionLength, offset)
                offset += radioLODeductionLength
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
//                offset += redundent
                val logBytes = cutByteArray(byteArray, logModeLength, offset)
                offset += logModeLength
                val antennaBytes = cutByteArray(byteArray, antennaTypeLength, offset)
                offset += antennaTypeLength
                val tOutBytes = cutByteArray(byteArray, tOutLength, offset)
                offset += tOutLength
                val frequencyXcvr3SatelliteTXBytes = cutByteArray(byteArray, frequencyXcvr3SatelliteTXBytesLength, offset)
                offset += frequencyXcvr3SatelliteTXBytesLength
                val frequencyXcvr4SatelliteTXBytes = cutByteArray(byteArray, frequencyXcvr4SatelliteTXBytesLength, offset)
                offset += frequencyXcvr4SatelliteTXBytesLength
                val frequencyXcvr3SatelliteRXBytes = cutByteArray(byteArray, frequencyXcvr3SatelliteRXBytesLength, offset)
                offset += frequencyXcvr3SatelliteRXBytesLength
                val frequencyXcvr4SatelliteRXBytes = cutByteArray(byteArray, frequencyXcvr4SatelliteRXBytesLength, offset)
                offset += frequencyXcvr4SatelliteRXBytesLength
                val powerXcvr3TXBytes = cutByteArray(byteArray, powerXcvr3TXLength, offset)
                offset += powerXcvr3TXLength
                val powerXcvr4TXBytes = cutByteArray(byteArray, powerXcvr4TXLength, offset)
                offset += powerXcvr4TXLength
                val radioXcvr4DeductionBytes = cutByteArray(byteArray, radioXcvr4DeductionLength, offset)
                offset += radioXcvr4DeductionLength
                val functionXcvr1Bytes = cutByteArray(byteArray, functionXcvr1Length, offset)
                offset += functionXcvr1Length
                val functionXcvr2Bytes = cutByteArray(byteArray, functionXcvr2Length, offset)
                offset += functionXcvr2Length
                val functionXcvr3Bytes = cutByteArray(byteArray, functionXcvr3Length, offset)
                offset += functionXcvr3Length
                val transmitterModeBytes = cutByteArray(byteArray, transmitterModeLength, offset)
                offset += transmitterModeLength
                val frequencyLOTX = cutByteArray(byteArray, frequencyLOTXLength, offset)
                offset += frequencyLOTXLength
                val frequencyLORX = cutByteArray(byteArray, frequencyLORXLength, offset)
                offset += frequencyLORXLength
                val frequencyXcvr2TXBytes = cutByteArray(byteArray, frequencyXcvr2TXLength, offset)
                offset += frequencyXcvr2TXLength
                val frequencyXcvr2RXBytes = cutByteArray(byteArray, frequencyXcvr2RXLength, offset)
                offset += frequencyXcvr2RXLength
                val powerBattery = cutByteArray(byteArray, powerBatteryLength, offset)
                offset += powerBatteryLength
                val batteryChargeStatus = cutByteArray(byteArray, batteryChargeStatusLength, offset)
                offset += batteryChargeStatusLength
                val mcuTemperature = cutByteArray(byteArray, mcuTemperatureLength, offset)
                offset += mcuTemperatureLength
                val rdpLevel = cutByteArray(byteArray, rdpLevelLength, offset)
                offset += rdpLevelLength
                val frequencyXcvr3TXBytes = cutByteArray(byteArray, frequencyXcvr3TXLength, offset)
                offset += frequencyXcvr3TXLength
                val frequencyXcvr3RXBytes = cutByteArray(byteArray, frequencyXcvr3RXLength, offset)
                offset += frequencyXcvr3RXLength
                val frequencyXcvr4TXBytes = cutByteArray(byteArray, frequencyXcvr4TXLength, offset)
                offset += frequencyXcvr4TXLength
                val frequencyXcvr4RXBytes = cutByteArray(byteArray, frequencyXcvr4RXLength, offset)
                offset += frequencyXcvr4RXLength
                val deviceTypeBytes = cutByteArray(byteArray, deviceTypeLength, offset)
                offset += deviceTypeLength
                val power12V = cutByteArray(byteArray, power12VLength, offset)
                offset += power12VLength

                val bittelConfigurationPackage = StardustConfigurationPackage(
                    frequencyXcvr1SatelliteTXBytes = byteArrayToUInt32(frequencyHRSatelliteTXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1RadioTXBytes = byteArrayToUInt32(frequencyHRRadioTXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2SatelliteTXBytes = byteArrayToUInt32(frequencyLRSatelliteTXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1SatelliteRXBytes = byteArrayToUInt32(frequencyHRSatelliteRXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1RadioRXBytes = byteArrayToUInt32(frequencyHRRadioRXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2SatelliteRXBytes = byteArrayToUInt32(frequencyLRSatelliteRXBytes.reversedArray()).toDouble().div(MHz),
                    powerLOTX = byteArrayToInt(powerLOTX.reversedArray()),
                    powerLORX = byteArrayToInt(powerLORX.reversedArray()),
                    powerXcvr1TX = byteArrayToInt(powerHRTX.reversedArray()),
                    powerXcvr2TX = byteArrayToInt(powerLRTX.reversedArray()),
                    radioLODeduction = byteArrayToFloat(radioLODeduction.reversedArray()),
                    bittelType = StardustType.values()[byteArrayToInt(bittelType)],
                    portType = getPortType(byteArrayToInt(portType)),
                    crcType = byteArrayToInt(crcType),
                    serverByteType = byteArrayToInt(serverByteType),
                    debugIgnoreCanTransmit = byteArrayToBoolean(debugCanTrasmit),
                    snifferMode = SnifferMode.values()[byteArrayToInt(snifferModeBytes)],
                    stardustId = bittelIdBytes.reversedArray().toHex().substring(0,8),
                    appId = appIdBytes.reversedArray().toHex().substring(0,8),
                    antenna = AntennaType.values()[byteArrayToInt(antennaBytes)],
                    frequencyXcvr3SatelliteTXBytes = byteArrayToUInt32(frequencyXcvr3SatelliteTXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4SatelliteTXBytes = byteArrayToUInt32(frequencyXcvr4SatelliteTXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3SatelliteRXBytes = byteArrayToUInt32(frequencyXcvr3SatelliteRXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4SatelliteRXBytes = byteArrayToUInt32(frequencyXcvr4SatelliteRXBytes.reversedArray()).toDouble().div(MHz),
                    powerXcvr3TX = byteArrayToInt(powerXcvr3TXBytes.reversedArray()),
                    powerXcvr4TX = byteArrayToInt(powerXcvr4TXBytes.reversedArray()),
                    functionalityXcvr1 = StardustTypeFunctionality.values()[byteArrayToInt(functionXcvr1Bytes)],
                    functionalityXcvr2 = StardustTypeFunctionality.values()[byteArrayToInt(functionXcvr2Bytes)],
                    functionalityXcvr3 = StardustTypeFunctionality.values()[byteArrayToInt(functionXcvr3Bytes)],
                    frequencyLOTX = byteArrayToUInt32(frequencyLOTX.reversedArray()).toDouble().div(MHz),
                    frequencyLORX = byteArrayToUInt32(frequencyLORX.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2TX = byteArrayToUInt32(frequencyXcvr2TXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2RX = byteArrayToUInt32(frequencyXcvr2RXBytes.reversedArray()).toDouble().div(MHz),
                    powerBattery = byteArrayToFloat(powerBattery.reversedArray()),
                    batteryChargeStatus = StardustBatteryCharge.values()[byteArrayToInt(batteryChargeStatus)],
                    mcuTemperature = byteArrayToInt(mcuTemperature),
                    rdpLevel = StardustRDPLevel.values()[byteArrayToInt(rdpLevel)],
                    frequencyXcvr3TX = byteArrayToUInt32(frequencyXcvr3TXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3RX = byteArrayToUInt32(frequencyXcvr3RXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4TX = byteArrayToUInt32(frequencyXcvr4TXBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4RX = byteArrayToUInt32(frequencyXcvr4RXBytes.reversedArray()).toDouble().div(MHz),
                    power12V = byteArrayToFloat(power12V.reversedArray()),
                )
                return bittelConfigurationPackage
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