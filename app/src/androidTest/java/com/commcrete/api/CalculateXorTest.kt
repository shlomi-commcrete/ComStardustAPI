package com.commcrete.api

import com.commcrete.stardust.stardust.StardustPackageUtils
import org.junit.Test

class CalculateXorTest {

    @Test
    fun testCalculateXor () {


        val mutableList = mutableListOf(55, 101,  33, 132,   1,  19,  24, 128,12,  64,  35,   0,   0,  16,   1,  21,7,  58, 121, 165, 179, 161, 170,  97)
        val result = StardustPackageUtils.getCheckXor(mutableList)
        println("XOR: $result")
    }
}