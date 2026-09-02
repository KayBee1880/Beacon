package com.beacon.data

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromMessageDirection(value: MessageDirection): String = value.name

    @TypeConverter
    fun toMessageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
