package com.recordatorios.voz.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class AlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var reminderId: Long = -1
    private var reminderTitle: String = ""
    private var ttsDebug by mutableStateOf("Iniciando voz…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1)
        reminderTitle = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Recordatorio"
        val message = intent.getStringExtra("extra_message") ?: reminderTitle

        // Descarta la notificación full-screen ya que estamos mostrando la Activity.
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(reminderId.toInt())

        startAlarmSound()
        speakMessage(message)

        setContent {
            com.recordatorios.voz.ui.RecordatorioVozTheme {
                AlarmScreen(
                    message = message,
                    debugInfo = ttsDebug,
                    onSnooze = { minutes -> snooze(minutes) },
                    onDismiss = { dismiss() }
                )
            }
        }
    }

    private fun startAlarmSound() {
        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmActivity, alarmUri)
            isLooping = true
            setVolume(1.0f, 1.0f)
            prepare()
            start()
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )
    }

    private fun speakMessage(message: String) {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsDebug = "Motor de voz falló al iniciar (código $status)"
                return@TextToSpeech
            }

            val engine = tts ?: return@TextToSpeech
            val candidateLocales = listOf(Locale("es", "ES"), Locale("es"), Locale.getDefault())
            val workingLocale = candidateLocales.firstOrNull { locale ->
                val result = engine.setLanguage(locale)
                result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }

            if (workingLocale == null) {
                // Ningún idioma disponible: probablemente falta el paquete de voz.
                // Ofrecemos instalarlo para que la próxima alarma sí hable.
                ttsDebug = "Sin datos de voz en español instalados en el motor de TTS"
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                return@TextToSpeech
            }

            // Sin esto, el habla sale por el volumen de MÚSICA en vez del de
            // ALARMA: si la música está silenciada, no se escucha nada aunque
            // el motor sí esté "hablando".
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    ttsDebug = "Hablando ahora ($utteranceId)"
                }
                override fun onDone(utteranceId: String?) {
                    ttsDebug = "Terminó de hablar ($utteranceId)"
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    ttsDebug = "Error al reproducir el habla ($utteranceId)"
                }
            })

            ttsDebug = "Voz lista ($workingLocale), esperando para hablar…"

            CoroutineScope(Dispatchers.Main).launch {
                // Deja sonar la alarma un momento antes de hablar.
                kotlinx.coroutines.delay(1500)
                repeat(3) {
                    val result = engine.speak(message, TextToSpeech.QUEUE_ADD, null, "msg_$it")
                    if (result != TextToSpeech.SUCCESS) {
                        ttsDebug = "speak() devolvió error ($result)"
                    }
                }
            }
        }
    }

    private fun snooze(minutes: Long) {
        AlarmScheduler.scheduleSnooze(this, reminderId, reminderTitle, minutes)
        finishAlarm()
    }

    private fun dismiss() {
        finishAlarm()
    }

    private fun finishAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        finish()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun AlarmScreen(message: String, debugInfo: String, onSnooze: (Long) -> Unit, onDismiss: () -> Unit) {
    val alarmRedBright = com.recordatorios.voz.ui.AppAccentColors.alarmRedBright
    val alarmRedDeep = com.recordatorios.voz.ui.AppAccentColors.alarmRedDeep

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(alarmRedBright, alarmRedDeep),
                    center = androidx.compose.ui.geometry.Offset.Zero,
                    radius = 1600f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⏰",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "[debug voz] $debugInfo",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = { onSnooze(10) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = alarmRedBright
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Posponer 10 minutos")
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Descartar")
            }
        }
    }
}
