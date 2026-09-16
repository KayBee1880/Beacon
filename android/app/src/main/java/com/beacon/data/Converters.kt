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

    // Nullable, unlike the two above: Message.attachmentState is null for every plain
    // text message, the overwhelming majority of rows (Milestone 7, D-037).
    @TypeConverter
    fun fromAttachmentState(value: AttachmentState?): String? = value?.name

    @TypeConverter
    fun toAttachmentState(value: String?): AttachmentState? = value?.let { AttachmentState.valueOf(it) }
}
