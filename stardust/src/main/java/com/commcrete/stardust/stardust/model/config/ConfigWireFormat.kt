package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.util.ByteReader
import com.commcrete.stardust.util.Carrier

/** MHz divisor for raw frequency values on the wire. */
internal const val MHZ_DIVISOR = 1_000_000.0

/**
 * Version-varying part of the 0x8C configuration parse — currently only the per-transceiver record,
 * whose size and bit layout differ between firmware generations. The version-invariant tail is
 * parsed by [com.commcrete.stardust.stardust.model.StardustConfigurationParser].
 *
 * To add a future format: implement a new [ConfigWireFormat] and register it in
 * [ConfigFormatRegistry].
 */
interface ConfigWireFormat {

    /** Bytes per transceiver record. */
    val xcvrRecordSize: Int

    fun parseXcvr(reader: ByteReader, slot: Int): Xcvr

    /** Reads one preset (four transceivers) from the cursor. */
    fun parsePreset(reader: ByteReader, presetIndex: Int): Preset {
        val preset = Preset(index = presetIndex)
        preset.currentPreset = CurrentPreset.fromValue(presetIndex)
        for (slot in 0..3) {
            preset.xcvrList.add(parseXcvr(reader, slot))
        }
        return preset
    }

    /** HR/LR from functionality bit 0; xcvr slot 3 is always ST. Shared by all formats. */
    fun carrierTypeFor(slot: Int, functionalityByte: Int): CarrierType = when {
        slot == 3 -> CarrierType.ST
        (functionalityByte and 0x01) == 0 -> CarrierType.HR
        else -> CarrierType.LR
    }
}

/**
 * Legacy format (firmware < 24.0.7): 11-byte transceiver record. Carrier index is bits 0-1 of the
 * carrier byte; bandwidth is not on the wire (reported as -1).
 */
object LegacyConfigFormat : ConfigWireFormat {

    override val xcvrRecordSize = 11

    override fun parseXcvr(reader: ByteReader, slot: Int): Xcvr {
        val txFrequency = reader.u32le().toDouble() / MHZ_DIVISOR
        val rxFrequency = reader.u32le().toDouble() / MHZ_DIVISOR
        val power = reader.i8()

        val functionalityByte = reader.u8()
        val options = functionalityByte shr 1

        val carrierByte = reader.u8()
        val carrierIndex = carrierByte and 0b0000_0011           // bits 0-1
        val carrierOn = (carrierByte and 0b0000_0100) != 0       // bit 2
        val rdIndex = (carrierByte and 0b0001_1000) shr 3        // bits 3-4

        return Xcvr(
            txFrequency = txFrequency,
            rxFrequency = rxFrequency,
            power = power,
            options = options,
            carrier = Carrier(index = carrierIndex, type = carrierTypeFor(slot, functionalityByte)),
            carrierOn = carrierOn,
            rdIndex = rdIndex,
            bandwidth = -1
        )
    }
}

/**
 * Ver_24.0.7+ format: 12-byte transceiver record. The carrier byte carries carrier-on (bit 2),
 * RD index (bits 3-4) and bandwidth (bits 5-7); a trailing extended byte carries the extended
 * carrier index (F1-F9, lower nibble).
 */
object V24_0_7ConfigFormat : ConfigWireFormat {

    override val xcvrRecordSize = 12

    override fun parseXcvr(reader: ByteReader, slot: Int): Xcvr {
        val txFrequency = reader.u32le().toDouble() / MHZ_DIVISOR
        val rxFrequency = reader.u32le().toDouble() / MHZ_DIVISOR
        val power = reader.i8()

        val functionalityByte = reader.u8()
        val options = functionalityByte shr 1

        val carrierByte = reader.u8()
        val carrierOn = (carrierByte and 0b0000_0100) != 0       // bit 2
        val rdIndex = (carrierByte and 0b0001_1000) shr 3        // bits 3-4
        val bandwidth = (carrierByte shr 5) and 0b0000_0111      // bits 5-7

        val extByte = reader.u8()
        val carrierIndex = extByte and 0x0F                      // extended carrier 0-8

        return Xcvr(
            txFrequency = txFrequency,
            rxFrequency = rxFrequency,
            power = power,
            options = options,
            carrier = Carrier(index = carrierIndex, type = carrierTypeFor(slot, functionalityByte)),
            carrierOn = carrierOn,
            rdIndex = rdIndex,
            bandwidth = bandwidth
        )
    }
}

/**
 * Resolves the [ConfigWireFormat] for a firmware version. The table is ordered high-to-low by
 * minimum version; [resolve] picks the highest threshold the version satisfies, defaulting to
 * [LegacyConfigFormat] (including for an unknown/absent version).
 */
object ConfigFormatRegistry {

    private val table: List<Pair<FirmwareVersion, ConfigWireFormat>> = listOf(
        FirmwareVersion(24, 0, 7) to V24_0_7ConfigFormat,
        FirmwareVersion(0, 0, 0) to LegacyConfigFormat,
    )

    fun resolve(version: FirmwareVersion?): ConfigWireFormat {
        if (version == null) return LegacyConfigFormat
        return table.firstOrNull { version >= it.first }?.second ?: LegacyConfigFormat
    }
}
