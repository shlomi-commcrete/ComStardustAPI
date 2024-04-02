package com.commcrete.stardust.stardust.model

data class StardustLocationPackage (val latitude: Float , val longitude : Float,
    val height : Int, val year : Int, val month : Int, val day : Int ,
val hour : Int, val minute : Int, val second : Int)

data class StardustSOSPackage (val latitude: Float , val longitude : Float,
                                  val height : Int, val year : Int, val month : Int, val day : Int ,
                                  val hour : Int, val minute : Int, val second : Int, val sosType : Int)