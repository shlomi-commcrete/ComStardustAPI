package com.commcrete.stardust.stardust.model.config

enum class CurrentPreset(val value: Int) {
    PRESET1(0),
    PRESET2(1),
    PRESET3(2);

    companion object {
        fun fromValue(value: Int): CurrentPreset? {
            return entries.find { it.value == value }
        }
    }
}

enum class StardustType(val type: Int) {
    HANDHELD(0),
    VEHICLE(1),
    HANDHELD_VEHICLE(2),
    SERVER_VEHICLE(3);

    companion object {
        /**
         * Maps the raw device_type byte to a [StardustType]. Per the C reference the wire values are
         * not a clean 0..3 ordinal (`0/6 = Handheld, 4/7 = Vehicle`), so this cannot index [entries]
         * directly. Pending full firmware confirmation of the value table (see migration doc §8).
         */
        fun fromValue(value: Int): StardustType = when (value) {
            0, 6 -> HANDHELD
            4, 7 -> VEHICLE
            else -> entries.find { it.type == value } ?: HANDHELD
        }
    }
}

enum class PortType(val type: Int) {
    UNDEFINED(-1),
    BLUETOOTH_DISABLED_BLE(0),
    BLUETOOTH_DISABLED_USB(1),
    BLUETOOTH_ENABLED_BLE(2),
    BLUETOOTH_ENABLED_USB(3),
}

enum class StardustBatteryCharge(val type: Int) {
    NON_RECOVERABLE_FAULT(0),
    RECOVERABLE_FAULT(1),
    CHARGE_IN_PROGRESS(2),
    CHARGE_COMPLETED(3);

    companion object {
        fun fromValue(value: Int): StardustBatteryCharge =
            entries.find { it.type == value } ?: NON_RECOVERABLE_FAULT
    }
}

enum class AirEncryptionMode(val value: Int) {
    CBC(0),
    FIPS(1);

    companion object {
        /** Air-encryption mode is carried in bit 0 of the general-option byte. */
        fun fromValue(value: Int): AirEncryptionMode = if ((value and 0x01) == 1) FIPS else CBC
    }
}

enum class StardustRDPLevel(val type: Int) {
    MCU_READING_ENABLE(0),
    MCU_READING_DISABLE(1),
    MCU_READING_ERROR(2),
}

enum class SnifferMode(val type: Int) {
    DEFAULT(0),
    FUTURE_USE(1),
    ALL(2),
}

enum class AntennaType(val type: Int) {
    PASSIVE(0),
    ACTIVE(1),
}
