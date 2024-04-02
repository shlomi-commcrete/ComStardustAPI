package com.commcrete.stardust.location;

import com.commcrete.stardust.location.BittelLocationPackerKt;

import java.nio.ByteBuffer;

import kotlin.Triple;

// Class definition
public class Coordinates {
    // Constants
    private static final int LAT_RES = 24;
    private static final int LON_RES = 25;
    private static final int ALT_OFFSET = 1000;
    private static final int MAX_ALT = Short.MAX_VALUE - ALT_OFFSET;
    private static final int MIN_ALT = ALT_OFFSET * -1;
    private static final int ALT_ABOVE_UPPER_SCALE_ERR = Short.MAX_VALUE;
    private static final int ALT_BELOW_LOWER_SCALE_ERR = Short.MAX_VALUE - 1;
    private static final float LAT_FACTOR = 8388607.0f;
    private static final float LON_FACTOR = 8388607.0f;
    private static final float LATITUDE_ERR = 200.0f;
    private static final float LONGITUDE_ERR = 200.0f;
    private static final int ALTITUDE_ERR = Short.MAX_VALUE - 2;

    // Union class to reinterpret the float as an integer (32 bits) or array of 4 bytes
    private static class Union_32Bytes {
        float floatValue;
        int intValue;
        short[] words = new short[2];
        byte[] bytes = new byte[4];
    }

    // Union class to reinterpret the double as a long (64 bits) or array of 8 bytes
    private static class Union_64Bytes {
        double doubleValue;
        long longValue;
        int[] ints = new int[2];
        short[] words = new short[4];
        byte[] bytes = new byte[8];
    }

    // Constructor
    public Coordinates() {}

    // Public methods
    public long packGPSCoordData(float lat, float lon, short alt, byte[] coordBytes) {
        long packed = BittelLocationPackerKt.packGPS_Coord(lat, lon, alt);

        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(packed);
        System.arraycopy(buffer.array(), 0, coordBytes, 0, 8);

        return packed;
    }

    public long packGPSCoord(float lat, float lon, short alt) {
        long result = 0;
        long intLat = (long) (lat * LAT_FACTOR) >> 8;
        long intLon = (long) (lon * LON_FACTOR) >> 7;
        long altValue = packAltitude(alt);

        result = altValue | (intLon << 15) | (intLat << 40);

        return result;
    }

    public void unpackGPSCoordData(byte[] coordBytes, float[] latLonAlt) {
        ByteBuffer buffer = ByteBuffer.wrap(coordBytes);
        long packedGPS = buffer.getLong();

        Triple<Double, Double , Long> unpackedVal = BittelLocationPackerKt.unPackGPS_Coord(packedGPS);

        latLonAlt[0] = unpackedVal.getFirst().floatValue(); // Latitude
        latLonAlt[1] = unpackedVal.getSecond().floatValue(); // Longitude
        latLonAlt[2] = unpackedVal.getThird().floatValue(); // Altitude


    }

    public void unpackGPSCoord(long gpsData, float[] latLonAlt) {
        short alt = (short) (gpsData & 0x7FFF);
        latLonAlt[2] = unpackAltitude(alt);

        gpsData >>= 15;
        int intLon = (int) (gpsData & 0x1FFFFFF) << 7;
        latLonAlt[1] = (float) intLon / LON_FACTOR;

        gpsData >>= 25;
        long intLat = (gpsData & 0xFFFFFF) << 8;
        latLonAlt[0] = (float) intLat / LAT_FACTOR;
    }

    public double convertDMSToDD(int degree, int minutes, double seconds) {
        return degree + (double) minutes / 60.0 + seconds / 3600.0;
    }

    public void convertDDToDMS(double decimalDegrees, int[] dms) {
        dms[0] = (int) decimalDegrees;
        decimalDegrees -= dms[0];
        dms[1] = (int) (decimalDegrees * 60.0);
        dms[2] = (int) ((decimalDegrees * 60 - dms[1]) * 60.0);
    }

    // Private methods
    private long packAltitude(short altitude) {
        long alt;

        if (altitude != ALTITUDE_ERR) {
            if (altitude < -ALT_OFFSET) {
                alt = ALT_BELOW_LOWER_SCALE_ERR;
            } else if (altitude >= MAX_ALT) {
                alt = ALT_ABOVE_UPPER_SCALE_ERR;
            } else {
                altitude += ALT_OFFSET;
                alt = altitude;
            }
        } else {
            alt = altitude;
        }

        return alt;
    }

    private float unpackAltitude(short alt) {
        float altitude = alt;

        if (altitude != ALT_ABOVE_UPPER_SCALE_ERR && altitude != ALT_BELOW_LOWER_SCALE_ERR) {
            altitude -= ALT_OFFSET;
        }

        return altitude;
    }

}