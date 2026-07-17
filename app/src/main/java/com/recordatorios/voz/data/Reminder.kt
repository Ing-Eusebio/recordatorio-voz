package com.recordatorios.voz.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object Recurrence {
    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"

    // Repite el mismo día de semana en la misma posición del mes: si el
    // recordatorio cae el primer viernes, el próximo será el primer viernes
    // del mes siguiente (no el mismo número de día).
    const val MONTHLY_BY_WEEKDAY = "MONTHLY_BY_WEEKDAY"
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
