package com.recordatorios.voz.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.recordatorios.voz.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager pierde las alarmas programadas cuando el teléfono se reinicia.
 * Este receiver las vuelve a programar leyendo los recordatorios activos de Room.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(appContext).reminderDao()
            val upcoming = dao.getUpcoming(System.currentTimeMillis())
            upcoming.forEach { reminder ->
                AlarmScheduler.scheduleAll(appContext, reminder)
            }
        }
    }
}
