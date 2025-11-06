package com.commcrete.stardust.enums

enum class FunctionalityType (val bitwise : Int) {
    REPORTS(64),
    TEXT(2),
    LOCATION(4),
    PTT(1),
    BFT(8),
    FILE(16),
    IMAGE(32),
    ACK(-1),
}