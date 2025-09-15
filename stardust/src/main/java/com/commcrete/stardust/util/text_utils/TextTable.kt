package com.commcrete.bittell.util.text_utils

import com.commcrete.stardust.stardust.model.StardustControlByte
import timber.log.Timber
import kotlin.math.min


enum class NewAsciiTable(val char: Char, val hex: Int, val decimal: Int) {


    ALEF ('א', 0x1, 1),
    BET ('ב', 0x2, 2),
    GIMEL ('ג', 0x3, 3),
    DALET ('ד', 0x4, 4),
    HEI ('ה', 0x5, 5),
    VAV ('ו', 0x6, 6),
    ZAIN ('ז', 0x7, 7),
    HET ('ח', 0x8, 8),
    TET ('ט', 0x9, 9),
    YUD ('י', 0xB, 11),
    KAF ('כ', 0xC, 12),
    LAMED ('ל', 0xE, 14),
    MEM ('מ', 0xF, 15),
    NUN ('נ', 0x10, 16),
    SAMEH ('ס', 0x11, 17),
    AIN ('ע', 0x12, 18),
    PE ('פ', 0x13, 19),
    ZADIK ('צ', 0x14, 20),
    KUF ('ק', 0x15, 21),
    REISH ('ר', 0x16, 22),
    SHIN ('ש', 0x17, 23),
    TAF ('ת', 0x18, 24),
    PE_SOFIT ('ף', 0x19, 25),
    ZADIK_SOFIT ('ץ', 0x1A, 26),
    KAF_SOFIT ('ך', 0x1B, 27),
    NUN_SOFIT ('ן', 0x1C, 28),
    MEM_SOFIT ('ם', 0x1D, 29),
    SPACE(' ', 0x20, 32),
    EXCLAMATION_MARK('!', 0x21, 33),
    DOUBLE_QUOTES('"', 0x22, 34),
    HASH('#', 0x23, 35),
    DOLLAR('$', 0x24, 36),
    PERCENT('%', 0x25, 37),
    AMPERSAND('&', 0x26, 38),
    SINGLE_QUOTE('\'', 0x27, 39),
    CLOSE_PARENTHESIS('(', 0x29, 41),
    ASTERISK('*', 0x2A, 42),
    PLUS('+', 0x2B, 43),
    COMMA(',', 0x2C, 44),
    HYPHEN_MINUS('-', 0x2D, 45),
    PERIOD('.', 0x2E, 46),
    SLASH('/', 0x2F, 47),
    ZERO('0', 0x30, 48),
    ONE('1', 0x31, 49),
    TWO('2', 0x32, 50),
    THREE('3', 0x33, 51),
    FOUR('4', 0x34, 52),
    FIVE('5', 0x35, 53),
    SIX('6', 0x36, 54),
    SEVEN('7', 0x37, 55),
    EIGHT('8', 0x38, 56),
    NINE('9', 0x39, 57),
    COLON(':', 0x3A, 58),
    SEMICOLON(';', 0x3B, 59),
    LESS('<', 0x3C, 60),
    EQUALS('=', 0x3D, 61),
    MORE('>', 0x3E, 62),
    QUESTION_MARK('?', 0x3F, 63),
    AT_SIGN('@', 0x40, 64),
    UPPERCASE_A('A', 0x41, 65),
    UPPERCASE_B('B', 0x42, 66),
    UPPERCASE_C('C', 0x43, 67),
    UPPERCASE_D('D', 0x44, 68),
    UPPERCASE_E('E', 0x45, 69),
    UPPERCASE_F('F', 0x46, 70),
    UPPERCASE_G('G', 0x47, 71),
    UPPERCASE_H('H', 0x48, 72),
    UPPERCASE_I('I', 0x49, 73),
    UPPERCASE_J('J', 0x4A, 74),
    UPPERCASE_K('K', 0x4B, 75),
    UPPERCASE_L('L', 0x4C, 76),
    UPPERCASE_M('M', 0x4D, 77),
    UPPERCASE_N('N', 0x4E, 78),
    UPPERCASE_O('O', 0x4F, 79),
    UPPERCASE_P('P', 0x50, 80),
    UPPERCASE_Q('Q', 0x51, 81),
    UPPERCASE_R('R', 0x52, 82),
    UPPERCASE_S('S', 0x53, 83),
    UPPERCASE_T('T', 0x54, 84),
    UPPERCASE_U('U', 0x55, 85),
    UPPERCASE_V('V', 0x56, 86),
    UPPERCASE_W('W', 0x57, 87),
    UPPERCASE_X('X', 0x58, 88),
    UPPERCASE_Y('Y', 0x59, 89),
    UPPERCASE_Z('Z', 0x5A, 90),
    OPENING_BRACKET('[', 0x5B, 91),
    BACKSLASH('\\', 0x5C, 92),
    CLOSING_BRACKET(']', 0x5D, 93),
    CARET('^', 0x5E, 94),
    UNDERSCORE('_', 0x5F, 95),
    GRAVE_ACCENT('`', 0x60, 96),
    LOWERCASE_A('a', 0x61, 97),
    LOWERCASE_B('b', 0x62, 98),
    LOWERCASE_C('c', 0x63, 99),
    LOWERCASE_D('d', 0x64, 100),
    LOWERCASE_E('e', 0x65, 101),
    LOWERCASE_F('f', 0x66, 102),
    LOWERCASE_G('g', 0x67, 103),
    LOWERCASE_H('h', 0x68, 104),
    LOWERCASE_I('i', 0x69, 105),
    LOWERCASE_J('j', 0x6A, 106),
    LOWERCASE_K('k', 0x6B, 107),
    LOWERCASE_L('l', 0x6C, 108),
    LOWERCASE_M('m', 0x6D, 109),
    LOWERCASE_N('n', 0x6E, 110),
    LOWERCASE_O('o', 0x6F, 111),
    LOWERCASE_P('p', 0x70, 112),
    LOWERCASE_Q('q', 0x71, 113),
    LOWERCASE_R('r', 0x72, 114),
    LOWERCASE_S('s', 0x73, 115),
    LOWERCASE_T('t', 0x74, 116),
    LOWERCASE_U('u', 0x75, 117),
    LOWERCASE_V('v', 0x76, 118),
    LOWERCASE_W('w', 0x77, 119),
    LOWERCASE_X('x', 0x78, 120),
    LOWERCASE_Y('y', 0x79, 121),
    LOWERCASE_Z('z', 0x7A, 122),
    OPENING_BRACE('{', 0x7B, 123),
    VERTICAL_BAR('|', 0x7C, 124),
    CLOSING_BRACE('}', 0x7D, 125),
    TILDE('~', 0x7E, 126),
    ;

    override fun toString(): String {
        return "Character: $char, Hex: $hex, Decimal: $decimal"
    }

    companion object {
        fun getHexByChar(targetChar: Char): Int? {
            return values().find { it.char == targetChar }?.hex
        }

        fun getDecimalByChar(targetChar: Char): Int? {
            return values().find { it.char == targetChar }?.decimal
        }

        fun getCharByHex(targetHex: Int): Char? {
            return values().find { it.hex == targetHex }?.char
        }

        fun getCharByDecimal(targetDecimal: Int): Char? {
            return values().find { it.decimal == targetDecimal }?.char
        }
    }


}

fun getAsciiValue (string: String) : ByteArray {
    var byteList = mutableListOf<Byte>()
    for (char in string) {
        val asciiValue = NewAsciiTable.getHexByChar(char)
        if(asciiValue != null) {
            asciiValue.let {
                byteList.add(it.toByte())
            }

        }else {
            byteList.add(char.code.toByte())
        }
    }
    return byteList.toByteArray()
}

fun getCharValue (string: String?) : String {
    string?.let {
        val stringBuilder = StringBuilder()
        for (char in it) {
            if(char.toByte() < 30) {
                val asciiValue = NewAsciiTable.getCharByHex(char.toByte().toInt())
                stringBuilder.append(if(asciiValue != null) asciiValue else "")
            } else {
                stringBuilder.append(char)
            }
        }
        return stringBuilder.toString()
    }
    return ""
}

private fun generateAsciiString(): String {
    return NewAsciiTable.values().joinToString(separator = "") { it.char.toString() }
}

fun testConversions () {
    val string = generateAsciiString()

    val newString = "$string וואלה אני לא יודע מה קורה פה בוא נבדוק :( حرف\n "
    newString.forEach { char ->
        val hexValue = NewAsciiTable.getHexByChar(char)
        Timber.tag("testConversions").d("Char: $char, Hex: ${hexValue?.let { "0x" + it.toString(16).uppercase() } ?: "Not found"}")
    }
}

fun createDataByteArray(toByteArray: ByteArray) : ByteArray {
    var byteArray = ByteArray (toByteArray.size)
    var index = 0
    for (byte in toByteArray){
        byteArray[index] = byte
        index ++
    }
    return byteArray
}

fun splitMessage(data: Array<Int>): List<Array<Int>> {
    val chunkSize = 130
    val numberOfChunks = (data.size + chunkSize - 1) / chunkSize // Calculate how many full or partial chunks there will be

    val list = List(numberOfChunks) { index ->
        val start = index * chunkSize
        val end = min(start + chunkSize, data.size)
        data.sliceArray(start until end)
    }
    val listToReturn : MutableList<Array<Int>> = mutableListOf()
    for (item in list) {
        listToReturn.add(addElementAtStart(item, item.size))
    }
    return listToReturn
}

fun addElementAtStart(data: Array<Int>, elementToAdd: Int): Array<Int> {
    // Create a new array with one extra slot for the new element
    val result = Array(data.size + 1) { 0 }
    // Place the new element at the start of the new array
    result[0] = elementToAdd
    // Copy the original array into the new array starting from index 1
    System.arraycopy(data, 0, result, 1, data.size)
    return result
}

fun getIsAck (messageNum : Int, messagesNum : Int, isAck : Boolean = false) : StardustControlByte.StardustAcknowledgeType {
    if(messageNum == messagesNum) {
        if (isAck){
            return StardustControlByte.StardustAcknowledgeType.DEMAND_ACK
        }
    }
    return StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
}

fun getIsPartType (messageNum : Int, messagesNum : Int) : StardustControlByte.StardustPartType {
    if(messageNum != messagesNum) {
        return StardustControlByte.StardustPartType.MESSAGE
    }
    return StardustControlByte.StardustPartType.LAST
}
