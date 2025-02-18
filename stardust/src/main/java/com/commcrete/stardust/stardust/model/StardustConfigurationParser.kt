package com.commcrete.stardust.stardust.model

class StardustConfigurationParser : StardustParser() {

    companion object{
        const val sizeLength = 1

        const val preset1Xcvr1TXFreqLength = 4
        const val preset1Xcvr1RXFreqLength = 4
        const val preset1Xcvr1PowerLength = 1
        const val preset1Xcvr1RadioLength = 1
        const val preset1Xcvr2TXFreqLength = 4
        const val preset1Xcvr2RXFreqLength = 4
        const val preset1Xcvr2PowerLength = 1
        const val preset1Xcvr2RadioLength = 1
        const val preset1Xcvr3TXFreqLength = 4
        const val preset1Xcvr3RXFreqLength = 4
        const val preset1Xcvr3PowerLength = 1
        const val preset1Xcvr3RadioLength = 1
        const val preset1Xcvr4TXFreqLength = 4
        const val preset1Xcvr4RXFreqLength = 4
        const val preset1Xcvr4PowerLength = 1
        const val preset1Xcvr4RadioLength = 1

        const val preset2Xcvr1TXFreqLength = 4
        const val preset2Xcvr1RXFreqLength = 4
        const val preset2Xcvr1PowerLength = 1
        const val preset2Xcvr1RadioLength = 1
        const val preset2Xcvr2TXFreqLength = 4
        const val preset2Xcvr2RXFreqLength = 4
        const val preset2Xcvr2PowerLength = 1
        const val preset2Xcvr2RadioLength = 1
        const val preset2Xcvr3TXFreqLength = 4
        const val preset2Xcvr3RXFreqLength = 4
        const val preset2Xcvr3PowerLength = 1
        const val preset2Xcvr3RadioLength = 1
        const val preset2Xcvr4TXFreqLength = 4
        const val preset2Xcvr4RXFreqLength = 4
        const val preset2Xcvr4PowerLength = 1
        const val preset2Xcvr4RadioLength = 1

        const val preset3Xcvr1TXFreqLength = 4
        const val preset3Xcvr1RXFreqLength = 4
        const val preset3Xcvr1PowerLength = 1
        const val preset3Xcvr1RadioLength = 1
        const val preset3Xcvr2TXFreqLength = 4
        const val preset3Xcvr2RXFreqLength = 4
        const val preset3Xcvr2PowerLength = 1
        const val preset3Xcvr2RadioLength = 1
        const val preset3Xcvr3TXFreqLength = 4
        const val preset3Xcvr3RXFreqLength = 4
        const val preset3Xcvr3PowerLength = 1
        const val preset3Xcvr3RadioLength = 1
        const val preset3Xcvr4TXFreqLength = 4
        const val preset3Xcvr4RXFreqLength = 4
        const val preset3Xcvr4PowerLength = 1
        const val preset3Xcvr4RadioLength = 1

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
        const val appAddressLength = 4
        const val logModeLength = 2
        const val antennaTypeLength = 1
        const val tOutLength = 1
        const val SOSDataLength = 9
        const val radioLODeductionLength = 4
        const val radioXcvr4DeductionLength = 4
        const val bittelAddressLength = 4
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

    fun parseConfiguration(StardustPackage: StardustPackage) : StardustConfigurationPackage? {
        StardustPackage.data?.let { intArray ->
            try {
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 0
                val sizeBytes = cutByteArray(byteArray, sizeLength, offset)
                offset += sizeLength
                //Preset 1

                val preset1Xcvr1TXFreqBytes = cutByteArray(byteArray, preset1Xcvr1TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset1Xcvr1RXFreqBytes = cutByteArray(byteArray, preset1Xcvr1RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset1Xcvr1PowerBytes = cutByteArray(byteArray, preset1Xcvr1PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset1Xcvr1RadioBytes = cutByteArray(byteArray, preset1Xcvr1RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset1Xcvr2TXFreqBytes = cutByteArray(byteArray, preset1Xcvr2TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset1Xcvr2RXFreqBytes = cutByteArray(byteArray, preset1Xcvr2RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset1Xcvr2PowerBytes = cutByteArray(byteArray, preset1Xcvr2PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset1Xcvr2RadioBytes = cutByteArray(byteArray, preset1Xcvr2RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset1Xcvr3TXFreqBytes = cutByteArray(byteArray, preset1Xcvr3TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset1Xcvr3RXFreqBytes = cutByteArray(byteArray, preset1Xcvr3RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset1Xcvr3PowerBytes = cutByteArray(byteArray, preset1Xcvr3PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset1Xcvr3RadioBytes = cutByteArray(byteArray, preset1Xcvr3RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset1Xcvr4TXFreqBytes = cutByteArray(byteArray, preset1Xcvr4TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset1Xcvr4RXFreqBytes = cutByteArray(byteArray, preset1Xcvr4RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset1Xcvr4PowerBytes = cutByteArray(byteArray, preset1Xcvr4PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset1Xcvr4RadioBytes = cutByteArray(byteArray, preset1Xcvr4RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                //Preset 2

                val preset2Xcvr1TXFreqBytes = cutByteArray(byteArray, preset1Xcvr1TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset2Xcvr1RXFreqBytes = cutByteArray(byteArray, preset1Xcvr1RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset2Xcvr1PowerBytes = cutByteArray(byteArray, preset1Xcvr1PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset2Xcvr1RadioBytes = cutByteArray(byteArray, preset1Xcvr1RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset2Xcvr2TXFreqBytes = cutByteArray(byteArray, preset1Xcvr2TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset2Xcvr2RXFreqBytes = cutByteArray(byteArray, preset1Xcvr2RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset2Xcvr2PowerBytes = cutByteArray(byteArray, preset1Xcvr2PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset2Xcvr2RadioBytes = cutByteArray(byteArray, preset1Xcvr2RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset2Xcvr3TXFreqBytes = cutByteArray(byteArray, preset1Xcvr3TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset2Xcvr3RXFreqBytes = cutByteArray(byteArray, preset1Xcvr3RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset2Xcvr3PowerBytes = cutByteArray(byteArray, preset1Xcvr3PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset2Xcvr3RadioBytes = cutByteArray(byteArray, preset1Xcvr3RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset2Xcvr4TXFreqBytes = cutByteArray(byteArray, preset1Xcvr4TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset2Xcvr4RXFreqBytes = cutByteArray(byteArray, preset1Xcvr4RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset2Xcvr4PowerBytes = cutByteArray(byteArray, preset1Xcvr4PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset2Xcvr4RadioBytes = cutByteArray(byteArray, preset1Xcvr4RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                //Preset 3

                val preset3Xcvr1TXFreqBytes = cutByteArray(byteArray, preset1Xcvr1TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset3Xcvr1RXFreqBytes = cutByteArray(byteArray, preset1Xcvr1RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset3Xcvr1PowerBytes = cutByteArray(byteArray, preset1Xcvr1PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset3Xcvr1RadioBytes = cutByteArray(byteArray, preset1Xcvr1RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset3Xcvr2TXFreqBytes = cutByteArray(byteArray, preset1Xcvr2TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset3Xcvr2RXFreqBytes = cutByteArray(byteArray, preset1Xcvr2RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset3Xcvr2PowerBytes = cutByteArray(byteArray, preset1Xcvr2PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset3Xcvr2RadioBytes = cutByteArray(byteArray, preset1Xcvr2RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset3Xcvr3TXFreqBytes = cutByteArray(byteArray, preset1Xcvr3TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset3Xcvr3RXFreqBytes = cutByteArray(byteArray, preset1Xcvr3RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset3Xcvr3PowerBytes = cutByteArray(byteArray, preset1Xcvr3PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset3Xcvr3RadioBytes = cutByteArray(byteArray, preset1Xcvr3RadioLength, offset)
                offset += preset1Xcvr1RadioLength

                val preset3Xcvr4TXFreqBytes = cutByteArray(byteArray, preset1Xcvr4TXFreqLength, offset)
                offset += preset1Xcvr1TXFreqLength
                val preset3Xcvr4RXFreqBytes = cutByteArray(byteArray, preset1Xcvr4RXFreqLength, offset)
                offset += preset1Xcvr1RXFreqLength
                val preset3Xcvr4PowerBytes = cutByteArray(byteArray, preset1Xcvr4PowerLength, offset)
                offset += preset1Xcvr1PowerLength
                val preset3Xcvr4RadioBytes = cutByteArray(byteArray, preset1Xcvr4RadioLength, offset)
                offset += preset1Xcvr1RadioLength

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
                val bittelIdBytes = cutByteArray(byteArray, bittelAddressLength, offset)
                offset += bittelAddressLength
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
                    //Preset 1
                    frequencyXcvr1SatelliteTXBytesPreset1 = byteArrayToUInt32(preset1Xcvr1TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1SatelliteRXBytesPreset1 = byteArrayToUInt32(preset1Xcvr1RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1PowerBytesPreset1 = byteArrayToInt(preset1Xcvr1PowerBytes.reversedArray()),
                    functionalityXcvr1Preset1 = StardustTypeFunctionality.values()[byteArrayToInt(preset1Xcvr1RadioBytes)],

                    frequencyXcvr2SatelliteTXBytesPreset1 = byteArrayToUInt32(preset1Xcvr2TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2SatelliteRXBytesPreset1 = byteArrayToUInt32(preset1Xcvr2RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2PowerBytesPreset1 = byteArrayToInt(preset1Xcvr2PowerBytes.reversedArray()),
                    functionalityXcvr2Preset1 = StardustTypeFunctionality.values()[byteArrayToInt(preset1Xcvr2RadioBytes)],

                    frequencyXcvr3SatelliteTXBytesPreset1 = byteArrayToUInt32(preset1Xcvr3TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3SatelliteRXBytesPreset1 = byteArrayToUInt32(preset1Xcvr3RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3PowerBytesPreset1 = byteArrayToInt(preset1Xcvr3PowerBytes.reversedArray()),
                    functionalityXcvr3Preset1 = StardustTypeFunctionality.values()[byteArrayToInt(preset1Xcvr3RadioBytes)],

                    frequencyXcvr4SatelliteTXBytesPreset1 = byteArrayToUInt32(preset1Xcvr4TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4SatelliteRXBytesPreset1 = byteArrayToUInt32(preset1Xcvr4RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4PowerBytesPreset1 = byteArrayToInt(preset1Xcvr4PowerBytes.reversedArray()),
                    functionalityXcvr4Preset1 = StardustTypeFunctionality.values()[byteArrayToInt(preset1Xcvr4RadioBytes)],

                    //Preset 2
                    frequencyXcvr1SatelliteTXBytesPreset2 = byteArrayToUInt32(preset2Xcvr1TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1SatelliteRXBytesPreset2 = byteArrayToUInt32(preset2Xcvr1RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1PowerBytesPreset2 = byteArrayToInt(preset2Xcvr1PowerBytes.reversedArray()),
                    functionalityXcvr1Preset2 = StardustTypeFunctionality.values()[byteArrayToInt(preset2Xcvr1RadioBytes)],

                    frequencyXcvr2SatelliteTXBytesPreset2 = byteArrayToUInt32(preset2Xcvr2TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2SatelliteRXBytesPreset2 = byteArrayToUInt32(preset2Xcvr2RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2PowerBytesPreset2 = byteArrayToInt(preset2Xcvr2PowerBytes.reversedArray()),
                    functionalityXcvr2Preset2 = StardustTypeFunctionality.values()[byteArrayToInt(preset2Xcvr2RadioBytes)],

                    frequencyXcvr3SatelliteTXBytesPreset2 = byteArrayToUInt32(preset2Xcvr3TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3SatelliteRXBytesPreset2 = byteArrayToUInt32(preset2Xcvr3RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3PowerBytesPreset2 = byteArrayToInt(preset2Xcvr3PowerBytes.reversedArray()),
                    functionalityXcvr3Preset2 = StardustTypeFunctionality.values()[byteArrayToInt(preset2Xcvr3RadioBytes)],

                    frequencyXcvr4SatelliteTXBytesPreset2 = byteArrayToUInt32(preset2Xcvr4TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4SatelliteRXBytesPreset2 = byteArrayToUInt32(preset2Xcvr4RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4PowerBytesPreset2 = byteArrayToInt(preset2Xcvr4PowerBytes.reversedArray()),
                    functionalityXcvr4Preset2 = StardustTypeFunctionality.values()[byteArrayToInt(preset2Xcvr4RadioBytes)],

                    //Preset 3
                    frequencyXcvr1SatelliteTXBytesPreset3 = byteArrayToUInt32(preset3Xcvr1TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1SatelliteRXBytesPreset3 = byteArrayToUInt32(preset3Xcvr1RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr1PowerBytesPreset3 = byteArrayToInt(preset3Xcvr1PowerBytes.reversedArray()),
                    functionalityXcvr1Preset3 = StardustTypeFunctionality.values()[byteArrayToInt(preset3Xcvr1RadioBytes)],

                    frequencyXcvr2SatelliteTXBytesPreset3 = byteArrayToUInt32(preset3Xcvr2TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2SatelliteRXBytesPreset3 = byteArrayToUInt32(preset3Xcvr2RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr2PowerBytesPreset3 = byteArrayToInt(preset3Xcvr2PowerBytes.reversedArray()),
                    functionalityXcvr2Preset3 = StardustTypeFunctionality.values()[byteArrayToInt(preset3Xcvr2RadioBytes)],

                    frequencyXcvr3SatelliteTXBytesPreset3 = byteArrayToUInt32(preset3Xcvr3TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3SatelliteRXBytesPreset3 = byteArrayToUInt32(preset3Xcvr3RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr3PowerBytesPreset3 = byteArrayToInt(preset3Xcvr3PowerBytes.reversedArray()),
                    functionalityXcvr3Preset3 = StardustTypeFunctionality.values()[byteArrayToInt(preset3Xcvr3RadioBytes)],

                    frequencyXcvr4SatelliteTXBytesPreset3 = byteArrayToUInt32(preset3Xcvr4TXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4SatelliteRXBytesPreset3 = byteArrayToUInt32(preset3Xcvr4RXFreqBytes.reversedArray()).toDouble().div(MHz),
                    frequencyXcvr4PowerBytesPreset3 = byteArrayToInt(preset3Xcvr4PowerBytes.reversedArray()),
                    functionalityXcvr4Preset3 = StardustTypeFunctionality.values()[byteArrayToInt(preset3Xcvr4RadioBytes)],

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
                    antenna = AntennaType.values()[byteArrayToInt(antennaBytes)],
                    radioLODeduction = byteArrayToFloat(radioLODeduction.reversedArray()),
                    stardustId = bittelIdBytes.reversedArray().toHex().substring(0,8),
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

    private fun getPortType (portType : Int): PortType {
        PortType.values().iterator().forEach {
            if(it.type == portType) return it
        }
        return PortType.UNDEFINED
    }
}