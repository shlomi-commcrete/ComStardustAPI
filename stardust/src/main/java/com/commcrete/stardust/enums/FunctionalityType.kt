package com.commcrete.stardust.enums


enum class FunctionalityType (val bitwise : Int) {

    TEXT(2),
    LOCATION(4),
    PTT(1),
    BFT(8),
    FILE(16),
    IMAGE(32),
    REPORTS(64),
    ACK(-1),
    SOS(-1),
}