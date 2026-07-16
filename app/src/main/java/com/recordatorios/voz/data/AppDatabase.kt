package com.recordatorios.voz.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Reminder::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recordatorio_voz.db"
                )
                    // App de un solo usuario en desarrollo activo: en vez de
                    // escribir migraciones formales para cada cambio de
                    // esquema, se recrea la base de datos si cambia la
                    // versión. Esto borra los recordatorios guardados en esa
                    // actualización puntual.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
