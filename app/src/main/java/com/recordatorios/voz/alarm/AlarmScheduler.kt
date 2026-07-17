package com.recordatorios.voz.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.recordatorios.voz.data.Reminder

/**
 * Programa dos alarmas exactas por recordatorio:
 *  - una de "aviso" 15 minutos antes
 *  - una a la hora exacta del evento
 * Ambas usan AlarmManager.setAlarmClock: es el único modo que el sistema
 * trata como una alarma de reloj real y NO puede diferir por ahorro de
 * batería/Doze (setExactAndAllowWhileIdle admite retrasos de minutos, que
 * es lo que causaba que sonara 2-3 minutos tarde en Huawei).
 */
object AlarmScheduler {

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_KIND = "extra_kind"
    const val KIND_WARNING_15MIN = "warning_15min"
    const val KIND_EXACT = "exact"

    private const val WARNING_MINUTES_BEFORE = 15L

    fun scheduleAll(context: Context, reminder: Reminder) {
        scheduleExact(context, reminder)
        val warningTime = reminder.triggerAtMillis - WARNING_MINUTES_BEFORE * 60_000L
        if (warningTime > System.currentTimeMillis()) {
            scheduleWarning(context, reminder, warningTime)
        }
    }

    private fun scheduleExact(context: Context, reminder: Reminder) {
        schedule(
            context = context,
            triggerAtMillis = reminder.triggerAtMillis,
            reminderId = reminder.id,
            title = reminder.title,
            kind = KIND_EXACT,
            requestCode = requestCodeFor(reminder.id, KIND_EXACT)
        )
    }

    private fun scheduleWarning(context: Context, reminder: Reminder, atMillis: Long) {
        schedule(
            context = context,
            triggerAtMillis = atMillis,
            reminderId = reminder.id,
            title = reminder.title,
            kind = KIND_WARNING_15MIN,
            requestCode = requestCodeFor(reminder.id, KIND_WARNING_15MIN)
        )
    }

    fun scheduleSnooze(context: Context, reminderId: Long, title: String, minutesFromNow: Long) {
        val triggerAt = System.currentTimeMillis() + minutesFromNow * 60_000L
        schedule(
            context = context,
            triggerAtMillis = triggerAt,
            reminderId = reminderId,
            title = title,
            kind = KIND_EXACT,
            requestCode = requestCodeFor(reminderId, KIND_EXACT) + 1_000_000
        )
    }

    fun cancelAll(context: Context, reminderId: Long) {
        cancel(context, requestCodeFor(reminderId, KIND_EXACT))
        cancel(context, requestCodeFor(reminderId, KIND_WARNING_15MIN))
    }

    private fun schedule(
        context: Context,
        triggerAtMillis: Long,
        reminderId: Long,
        title: String,
        kind: String,
        requestCode: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_KIND, kind)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canScheduleExact) {
            // El "showIntent" es lo que se abre si el usuario toca el icono de
            // alarma en la barra de estado; lo mandamos a la app principal.
            val showIntent = PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, com.recordatorios.voz.ui.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                pendingIntent
            )
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancel(context: Context, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun requestCodeFor(reminderId: Long, kind: String): Int =
        (reminderId.toString() + kind).hashCode()
}
