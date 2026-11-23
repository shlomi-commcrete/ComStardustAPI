package com.commcrete.stardust.stardust.model

/**
 * Header metadata parsed from a V2 ("ST1") file-transfer start packet.
 *
 * [headerVector] is the raw 58-byte slot that becomes RS codeword index 0.
 * Note: data-class structural equality does not apply to [headerVector] (ByteArray
 * is compared by reference), which is fine — we never compare V2HeaderData instances.
 */
data class V2HeaderData(
    /** bits[7:5] of the category byte — 0=gzip/raw, 1-4=PigN method 0-3. */
    val encodingWireBits: Int,
    /** bits[4:0] of the category byte — 0=FILE, 1=IMAGE, 2=CONTACT. */
    val contentTypeBits: Int,
    val padding: Int,
    val fileExtension: String,
    val fileName: String,
    /** 3-byte big-endian original size; 0 means unknown (> 16 MiB or not sent). */
    val originalSize: Int,
    /** 0 = fresh header; k = copy injected after transmitting RS slot 3^k. */
    val copyLevel: Int,
    /** Raw 58-byte header_vector; becomes slot 0 of the RS codeword. */
    val headerVector: ByteArray,
)

/** One V2 data/parity packet parsed from the wire. */
data class V2DataPacket(
    /** 1-based RS slot index; equals the 0-based codeword index since slot 0 is the header. */
    val rsIndex: Int,
    /**
     * N (total slots) when [rsIndex] is odd; P (parity count) when [rsIndex] is even.
     * This is how the receiver discovers N and P without an explicit field in the header.
     */
    val meta: Int,
    /** 58-byte RS payload. */
    val payload: ByteArray,
)

/**
 * Parses V2 ("ST1") file-transfer packets from [StardustPackage.data] (Array<Int>, values 0-255).
 *
 * Wire layout (all packets are 63 bytes: data[0..62]):
 *   Header:  [0]=62  [1..3]="ST1"  [4..61]=header_vector(58 bytes)  [62]=copyLevel
 *   Data/P:  [0]=62  [1..2]=rsIndex(big-endian 1-based)  [3..4]=meta  [5..62]=payload(58 bytes)
 *
 * header_vector layout (58 bytes at data[4..61]):
 *   [0]  = category byte:  bits[7:5]=encodingWireBits  bits[4:0]=contentType
 *   [1]  = padding (bytes removed from the last data chunk)
 *   [2..6]  = file extension (5 bytes, zero-padded)
 *   [7..54] = file name     (48 bytes, zero-padded)
 *   [55..57]= original size (3 bytes, big-endian)
 */
class StardustFileStartParserV2 {

    fun parseHeader(mPackage: StardustPackage): V2HeaderData? {
        val d = mPackage.data ?: return null
        if (d.size < 63) return null
        // d[1..3] = "ST1" already matched by the caller
        val hv = ByteArray(58) { (d[4 + it] and 0xFF).toByte() }
        val category         = d[4] and 0xFF
        val encodingWireBits = (category ushr 5) and 0x07
        val contentTypeBits  = category and 0x1F
        val padding          = d[5] and 0xFF
        val extBytes  = ByteArray(5)  { (d[6  + it] and 0xFF).toByte() }
        val nameBytes = ByteArray(48) { (d[11 + it] and 0xFF).toByte() }
        val origSize  = ((d[59] and 0xFF) shl 16) or ((d[60] and 0xFF) shl 8) or (d[61] and 0xFF)
        val copyLevel = d[62] and 0xFF
        val ext  = String(extBytes.filter  { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        val name = String(nameBytes.filter { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        return V2HeaderData(encodingWireBits, contentTypeBits, padding, ext, name, origSize, copyLevel, hv)
    }

    fun parseDataPacket(mPackage: StardustPackage): V2DataPacket? {
        val d = mPackage.data ?: return null
        if (d.size < 63) return null
        val rsIndex = ((d[1] and 0xFF) shl 8) or (d[2] and 0xFF)
        if (rsIndex == 0) return null   // 0 would be the header slot; real data starts at 1
        val meta    = ((d[3] and 0xFF) shl 8) or (d[4] and 0xFF)
        val payload = ByteArray(58) { (d[5 + it] and 0xFF).toByte() }
        return V2DataPacket(rsIndex, meta, payload)
    }
}
