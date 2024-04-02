package com.commcrete.stardust.ble;

import java.util.UUID;

public class Characteristics {
    public static final UUID UUID_SERVICE_DEVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA00");
    public static final UUID WRITE_CHARACTERISTIC = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA00");
    public static final UUID IND_CHARACTERISTIC = UUID.fromString("359ccc38-6fea-11ed-a1eb-0242ac120002");
    public static final UUID REL_WRITE_CHARACTERISTIC = UUID.fromString("359ccc39-6fea-11ed-a1eb-0242ac120002");
    public static final UUID READ_CHARACTERISTIC = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA00");

    public static UUID getWriteChar(String id) {
        // Original logic commented out. Adjust as necessary.
        // return UUID.fromString(WRITE_CHARACTERISTIC.toString().replace("9e", id));
        return WRITE_CHARACTERISTIC;
    }

    public static UUID getReadChar(String id) {
        // Original logic commented out. Adjust as necessary.
        // return UUID.fromString(READ_CHARACTERISTIC.toString().replace("9e", id));
        return READ_CHARACTERISTIC;
    }

    public static UUID getConnectChar(String id) {
        // Original logic commented out. Adjust as necessary.
        // return UUID.fromString(UUID_SERVICE_DEVICE.toString().replace("9e", id));
        return UUID_SERVICE_DEVICE;
    }

}
