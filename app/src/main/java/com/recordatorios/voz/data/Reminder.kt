package com.recordatorios.voz.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object Recurrence {
    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
}

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAtMillis: Long,
    val dismissed: Boolean = false,
    val recurrence: String = Recurrence.NONE,
    val note: String? = null
)
