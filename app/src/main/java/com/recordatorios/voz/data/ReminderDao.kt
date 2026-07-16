package com.recordatorios.voz.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    // Se traen todos y se clasifican en activos/historial del lado de la UI
    // comparando triggerAtMillis contra la hora actual, para que un
    // recordatorio pase a historial apenas se cumple su fecha/hora, sin
    // depender de que la alarma haya sonado.
    @Query("SELECT * FROM reminders ORDER BY triggerAtMillis ASC")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    @Query("SELECT * FROM reminders WHERE triggerAtMillis > :from")
    suspend fun getUpcoming(from: Long): List<Reminder>

    // Recordatorios cuyo horario está a menos de `thresholdMillis` de distancia
    // del horario propuesto, usado para avisar de posibles conflictos.
    @Query("SELECT * FROM reminders WHERE ABS(triggerAtMillis - :targetMillis) <= :thresholdMillis AND id != :excludeId")
    suspend fun findNearby(targetMillis: Long, thresholdMillis: Long, excludeId: Long): List<Reminder>

    // Borra permanentemente el historial (recordatorios ya vencidos y sin
    // recurrencia, ya que los recurrentes nunca quedan "vencidos": se
    // reprograman solos).
    @Query("DELETE FROM reminders WHERE triggerAtMillis <= :now AND recurrence = 'NONE'")
    suspend fun deleteHistoryBefore(now: Long)
}
