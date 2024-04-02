package com.commcrete.stardust.stardust.model

data class StardustLogPackage(
    val counter: Int, val syncClock: Int,
    val gpsTime: StardustGPSTimePackage, val type: StardustLogParser.PARSE_DATA_TYPE?, val code: StardustLogParser.PARSE_CODE_TYPE?,
    val size: Int, val data: ByteArray
)