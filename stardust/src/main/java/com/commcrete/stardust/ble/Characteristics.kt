package no.nordicsemi.andorid.ble.test.spec

import java.util.*

object Characteristics {
    /** A random UUID. */
    val UUID_SERVICE_DEVICE: UUID by lazy { UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA00") }
    val WRITE_CHARACTERISTIC: UUID by lazy { UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA00") }
    val IND_CHARACTERISTIC: UUID by lazy { UUID.fromString("359ccc38-6fea-11ed-a1eb-0242ac120002") }
    val REL_WRITE_CHARACTERISTIC: UUID by lazy { UUID.fromString("359ccc39-6fea-11ed-a1eb-0242ac120002") }
    val READ_CHARACTERISTIC: UUID by lazy { UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA00") }


    fun getWriteChar(id: String): UUID {
//        return UUID.fromString(WRITE_CHARACTERISTIC.toString().replace("9e", id))
        return UUID.fromString(WRITE_CHARACTERISTIC.toString())
    }

    fun getReadChar(id: String): UUID {
//        return UUID.fromString(READ_CHARACTERISTIC.toString().replace("9e", id))
        return UUID.fromString(READ_CHARACTERISTIC.toString())
    }

    fun getConnectChar(id: String): UUID {
//        return UUID.fromString(UUID_SERVICE_DEVICE.toString().replace("9e", id))
        return UUID.fromString(UUID_SERVICE_DEVICE.toString())
    }
}