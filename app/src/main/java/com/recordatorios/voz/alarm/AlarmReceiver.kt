package com.recordatorios.voz.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import com.recordatorios.voz.data.AppDatabase
import com.recordatorios.voz.data.Recurrence
import com.recordatorios.voz.data.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Recibe el disparo del AlarmManager. No podemos abrir una Activity directamente
 * desde background en Android 10+, así que publicamos una notificación de máxima
 * prioridad con fullScreenIntent: el sistema la muestra a pantalla completa
 * automáticamente si el teléfono está bloqueado, igual que una alarma nativa.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Recordatorio"
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: AlarmScheduler.KIND_EXACT

        val userName = UserPrefs.getName(context)
        val namePrefix = if (!userName.isNullOrBlank()) "$userName, " else ""

        val message = if (kind == AlarmScheduler.KIND_WARNING_15MIN)
            "$namePrefix$title EN 15 MINUTOS"
        else
            "$namePrefix$title"

        createChannel(context)

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(AlarmScheduler.EXTRA_TITLE, title)
            putExtra("extra_message", message)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, reminderId.toInt(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Recordatorio")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(reminderId.toInt(), notification)

        // También intentamos lanzar la Activity directamente por si el sistema
        // permite background start (p.ej. pantalla ya encendida y app reciente).
        context.startActivity(fullScreenIntent)

        if (kind == AlarmScheduler.KIND_EXACT) {
            rescheduleIfRecurring(context, reminderId)
        }
    }

    /**
     * Si el recordatorio que acaba de sonar es recurrente, calcula la
     * siguiente fecha/hora, la guarda en la base de datos y programa la
     * próxima alarma — así nunca "vence" y queda repitiéndose solo.
     */
    private fun rescheduleIfRecurring(context: Context, reminderId: Long) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(appContext).reminderDao()
                val reminder = dao.getById(reminderId) ?: return@launch
                if (reminder.recurrence == Recurrence.NONE) return@launch

                val current = Instant.ofEpochMilli(reminder.triggerAtMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime()
                val next = when (reminder.recurrence) {
                    Recurrence.DAILY -> current.plusDays(1)
                    Recurrence.WEEKLY -> current.plusWeeks(1)
                    Recurrence.MONTHLY -> current.plusMonths(1)
                    Recurrence.MONTHLY_BY_WEEKDAY -> nextMonthlySameWeekday(current)
                    else -> current
                }
                val nextMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                val updated = reminder.copy(triggerAtMillis = nextMillis)
                dao.update(updated)
                AlarmScheduler.scheduleAll(appContext, updated)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Calcula la misma posición de día de semana en el mes siguiente:
     * p. ej. si `current` es el primer viernes de julio, devuelve el primer
     * viernes de agosto. Si el mes siguiente no tiene una 5.ª ocurrencia,
     * usa la última disponible.
     */
    private fun nextMonthlySameWeekday(current: java.time.LocalDateTime): java.time.LocalDateTime {
        val weekday = current.dayOfWeek
        val ordinal = (current.dayOfMonth - 1) / 7 + 1

        val nextMonth = current.toLocalDate().plusMonths(1).withDayOfMonth(1)
        var candidate = nextMonth
        while (candidate.dayOfWeek != weekday) candidate = candidate.plusDays(1)
        candidate = candidate.plusWeeks((ordinal - 1).toLong())
        if (candidate.month != nextMonth.month) {
            candidate = candidate.minusWeeks(1)
        }
        return candidate.atTime(current.toLocalTime())
    }

    private fun createChannel(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID, "Alarmas de recordatorios", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notificaciones de alarma a pantalla completa"
            setSound(
                android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                audioAttributes
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "alarm_channel"
    }
}
