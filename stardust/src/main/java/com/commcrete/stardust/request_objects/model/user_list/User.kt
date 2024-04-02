package com.commcrete.stardust.request_objects.model.user_list

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters
import kotlinx.android.parcel.Parcelize

@Entity(tableName = "friends_table", indices = [Index(value = ["phone"], unique = true)])
@Parcelize
data class User(
    val __v: Int = 0,
    @PrimaryKey()
    val _id: String = "",
    @TypeConverters(Converters.StringArrayConverter::class)
    val appId: Array<String>? = arrayOf(),
    @TypeConverters(Converters.StringArrayConverter::class)
    val bittelId: Array<String>? = arrayOf(),
    var bittelName : String = "",
    var bittelMacAddress : String = "",
    val createdAt: String = "",
    var displayName: String = "",
    val isOnline: Boolean = false,
    @TypeConverters(Converters.DoubleArrayConverter::class)
    val location: List<Double> = listOf(),
    val phone: String = "",
    val licenceType : String = "",
    val profileImageUrl: String = "",
    val pttEnabled: Boolean = false,
    val token : String = "",
    val status: String = "",
    val updatedAt: String = "",
    var uri : String? = null
) : Parcelable
