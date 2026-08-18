package com.commcrete.stardust.stardust.model

import android.util.Log
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.stardust.model.config.AirEncryptionMode
import com.commcrete.stardust.stardust.model.config.AntennaType
import com.commcrete.stardust.stardust.model.config.ConfigFormatRegistry
import com.commcrete.stardust.stardust.model.config.CurrentPreset
import com.commcrete.stardust.stardust.model.config.FirmwareVersion
import com.commcrete.stardust.stardust.model.config.LeaseCalculator
import com.commcrete.stardust.stardust.model.config.PortType
import com.commcrete.stardust.stardust.model.config.SnifferMode
import com.commcrete.stardust.stardust.model.config.StardustBatteryCharge
import com.commcrete.stardust.stardust.model.config.StardustRDPLevel
import com.commcrete.stardust.stardust.model.config.StardustType
import com.commcrete.stardust.util.ByteReader

class StardustConfigurationParser : StardustParser() {

    companion object {
        const val sizeLength = 1
        const val SOSDataLength = 9
        const val deviceModelLength = 14
        const val deviceSerialLength = 14
        const val MHz = 1000000
    }

    /**
     * Parses a 0x8C configuration payload. [firmwareVersion] is the device firmware string (e.g.
     * "Ver_24.0.7"); it selects the transceiver wire format. A null/unparseable version parses as
     * the legacy format.
     */
    fun parseConfiguration(pkg: StardustPackage, firmwareVersion: String? = null): StardustConfigurationPackage? {
        val intArray = pkg.data
        if (intArray == null) {
            Log.w("ConfigDebug", "parseConfiguration: pkg.data is NULL (opCode=${pkg.stardustOpCode}) -> returning null")
            return null
        }
        Log.d("ConfigDebug", "parseConfiguration: parsing data size=${intArray.size} fw=$firmwareVersion data=[${intArray.joinToString(" ") { "%02X".format(it and 0xFF) }}]")
        try {
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            val format = ConfigFormatRegistry.resolve(FirmwareVersion.parse(firmwareVersion))
            val reader = ByteReader(byteArray)

            reader.skip(sizeLength)

            // Presets (3), transceiver record size depends on the resolved format.
            val presets = (0..2).map { format.parsePreset(reader, it) }

            // LO outputs.
            val frequencyLOTX = reader.u32le().toDouble().div(MHz)
            val frequencyLORX = reader.u32le().toDouble().div(MHz)
            val powerLOTX = reader.i8()
            val powerLORX = reader.i8()

            val currentPreset = CurrentPreset.entries[reader.u8()]
            val gpsEnabled = reader.u8() != 0
            val portType = getPortType(reader.u8())
            val crcType = reader.u8()
            val airEncryptionMode = AirEncryptionMode.fromValue(reader.u8())
            reader.skip(1) // reserved / empty byte
            val snifferMode = SnifferMode.entries[reader.u8()]
            val stardustId = reader.hexReversed(4).substring(0, 8)
            val appId = reader.hexReversed(4).substring(0, 8)
            val logDebugMode = reader.u8()
            val logStreamEnable = reader.u8()
            val antenna = AntennaType.entries[reader.u8()]
            reader.skip(1) // timeout
            val sosBytes = reader.bytes(SOSDataLength)
            val radioLODeduction = reader.f32le()
            val radioXcvr4Deduction = reader.f32le()
            val deviceModel = reader.utf8(deviceModelLength)
            val deviceSerial = reader.utf8(deviceSerialLength)
            val licenseType = reader.u8().let { licenceNumber ->
                LicenseType.entries.find { it.type == licenceNumber } ?: LicenseType.UNDEFINED
            }
            val relayMode = reader.u8()
            val power12V = reader.f32le()
            val powerBattery = reader.f32le()
            // The device includes a battery-charge-status byte here even though the C reference
            // (config_parser.c) omits it — confirmed against a live 233-byte 24.0.7 payload.
            val batteryChargeStatus = StardustBatteryCharge.fromValue(reader.u8())
            val mcuTemperature = reader.i8()
            val rdpLevel = StardustRDPLevel.entries[reader.u8()]
            val stardustType = StardustType.fromValue(reader.u8()) // device_type, last byte

            presets.forEach { it.leases = LeaseCalculator.calculatePresetLeases(it) }

            return StardustConfigurationPackage(
                presets = presets,
                frequencyLOTX = frequencyLOTX,
                frequencyLORX = frequencyLORX,
                powerLOTX = powerLOTX,
                powerLORX = powerLORX,
                currentPreset = currentPreset,
                gpsEnabled = gpsEnabled,
                stardustType = stardustType,
                portType = portType,
                crcType = crcType,
                airEncryptionMode = airEncryptionMode,
                snifferMode = snifferMode,
                appId = appId,
                stardustId = stardustId,
                logDebugMode = logDebugMode,
                logStreamEnable = logStreamEnable,
                antenna = antenna,
                radioLODeduction = radioLODeduction,
                radioXcvr4Deduction = radioXcvr4Deduction,
                relayMode = relayMode,
                power12V = power12V,
                powerBattery = powerBattery,
                batteryChargeStatus = batteryChargeStatus,
                mcuTemperature = mcuTemperature,
                rdpLevel = rdpLevel,
                licenseType = licenseType,
                deviceModel = deviceModel,
                deviceSerial = deviceSerial,
                sosXCVR = sosBytes[0].toInt() and 0x03,
                sosDestinations = parseSosDestinations(sosBytes),
            )
        } catch (e: Exception) {
            Log.w("ConfigDebug", "parseConfiguration THREW while parsing (data size=${intArray.size}): ${e.message}", e)
            e.printStackTrace()
        }
        return null
    }

    private fun parseSosDestinations(bytes: ByteArray): List<String> {
        val headerSize = 1
        val idSize = 4

        if (bytes.size < SOSDataLength) return emptyList()

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

    private fun getPortType(portType: Int): PortType {
        return PortType.entries.find { it.type == portType } ?: PortType.UNDEFINED
    }

}
