package com.recordatorios.voz.ui

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.recordatorios.voz.alarm.AlarmScheduler
import com.recordatorios.voz.data.AppDatabase
import com.recordatorios.voz.data.Recurrence
import com.recordatorios.voz.data.Reminder
import com.recordatorios.voz.data.UserPrefs
import com.recordatorios.voz.parser.LocalReminderParser
import com.recordatorios.voz.parser.ParseException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private const val CONFLICT_THRESHOLD_MILLIS = 30 * 60 * 1000L

private data class ConflictRequest(
    val conflicts: List<Reminder>,
    val onConfirm: () -> Unit,
    val onReschedule: (newMillis: Long) -> Unit
)

class MainActivity : ComponentActivity() {

    private val statusFlow = MutableStateFlow("Dicta o escribe tu recordatorio")
    private val dao by lazy { AppDatabase.get(this).reminderDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Deja que nuestro degradado se dibuje detrás de la barra de estado
        // del sistema, en vez de dejar una franja blanca separada arriba.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        suspend fun commitCreate(title: String, triggerAtMillis: Long, recurrence: String, note: String?) {
            val reminder = Reminder(
                title = title,
                triggerAtMillis = triggerAtMillis,
                recurrence = recurrence,
                note = note
            )
            val id = dao.insert(reminder)
            AlarmScheduler.scheduleAll(this@MainActivity, reminder.copy(id = id))
            statusFlow.value = "Listo: \"$title\" — ${formatDate(triggerAtMillis)}"
        }

        suspend fun commitUpdate(
            reminder: Reminder,
            newTitle: String,
            newMillis: Long,
            newRecurrence: String,
            newNote: String?
        ) {
            AlarmScheduler.cancelAll(this@MainActivity, reminder.id)
            val updated = reminder.copy(
                title = newTitle,
                triggerAtMillis = newMillis,
                recurrence = newRecurrence,
                note = newNote
            )
            dao.update(updated)
            AlarmScheduler.scheduleAll(this@MainActivity, updated)
            statusFlow.value = "Actualizado: \"${updated.title}\" — ${formatDate(updated.triggerAtMillis)}"
        }

        // Revisa conflictos para `triggerAtMillis`; si hay, ofrece confirmar,
        // cancelar, o elegir otra fecha/hora (que vuelve a pasar por aquí,
        // así que un segundo conflicto también se detecta).
        suspend fun tryCommit(
            triggerAtMillis: Long,
            excludeId: Long,
            onConflict: (ConflictRequest) -> Unit,
            commit: suspend (Long) -> Unit
        ) {
            val conflicts = dao.findNearby(triggerAtMillis, CONFLICT_THRESHOLD_MILLIS, excludeId)
            if (conflicts.isEmpty()) {
                commit(triggerAtMillis)
            } else {
                onConflict(
                    ConflictRequest(
                        conflicts = conflicts,
                        onConfirm = { lifecycleScope.launch { commit(triggerAtMillis) } },
                        onReschedule = { newMillis ->
                            lifecycleScope.launch { tryCommit(newMillis, excludeId, onConflict, commit) }
                        }
                    )
                )
            }
        }

        fun processUtterance(
            text: String,
            overrideMillis: Long?,
            manualRecurrence: String,
            processingSetter: (Boolean) -> Unit,
            onConflict: (ConflictRequest) -> Unit
        ) {
            if (text.isBlank()) return
            processingSetter(true)
            lifecycleScope.launch {
                try {
                    val title: String
                    val triggerAtMillis: Long
                    val recurrence: String
                    if (overrideMillis != null) {
                        title = LocalReminderParser.titleOnly(text)
                        triggerAtMillis = overrideMillis
                        recurrence = manualRecurrence
                    } else {
                        val parsed = LocalReminderParser.parse(text)
                        title = parsed.title
                        triggerAtMillis = parsed.triggerAtMillis
                        // Si el usuario eligió la recurrencia a mano, esa manda
                        // sobre lo que se haya detectado en el texto/voz.
                        recurrence = if (manualRecurrence != Recurrence.NONE) manualRecurrence else parsed.recurrence
                    }

                    tryCommit(triggerAtMillis, -1L, onConflict) { millis ->
                        commitCreate(title, millis, recurrence, null)
                    }
                } catch (e: ParseException) {
                    statusFlow.value = "No pude interpretar la fecha/hora: ${e.message}"
                } catch (e: Exception) {
                    statusFlow.value = "Error inesperado: ${e.message}"
                } finally {
                    processingSetter(false)
                }
            }
        }

        setContent {
            val status by statusFlow.asStateFlow().collectAsState()
            val allReminders by dao.observeAll().collectAsState(initial = emptyList())
            var processing by remember { mutableStateOf(false) }
            var selectedReminder by remember { mutableStateOf<Reminder?>(null) }
            var pendingConflict by remember { mutableStateOf<ConflictRequest?>(null) }
            var textInput by remember { mutableStateOf("") }
            var manualDate by remember { mutableStateOf<LocalDate?>(null) }
            var manualTime by remember { mutableStateOf<LocalTime?>(null) }
            var manualRecurrence by remember { mutableStateOf(Recurrence.NONE) }
            var searchQuery by remember { mutableStateOf("") }
            var showClearHistoryConfirm by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableStateOf(0) }
            var userName by remember { mutableStateOf(UserPrefs.getName(this@MainActivity)) }

            // Se recalcula cada 30s para que un recordatorio pase solo a
            // "Historial" apenas se cumple su hora, sin reiniciar la app.
            var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(30_000)
                    nowTick = System.currentTimeMillis()
                }
            }
            val activeReminders = remember(allReminders, nowTick, searchQuery) {
                allReminders
                    .filter { it.triggerAtMillis > nowTick }
                    .filter { it.matchesSearch(searchQuery) }
                    .sortedBy { it.triggerAtMillis }
            }
            val historyReminders = remember(allReminders, nowTick, searchQuery) {
                allReminders
                    .filter { it.triggerAtMillis <= nowTick }
                    .filter { it.matchesSearch(searchQuery) }
                    .sortedByDescending { it.triggerAtMillis }
            }

            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { importBackup(it) } }

            val speechLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val spokenText = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spokenText.isNullOrBlank()) {
                    processUtterance(spokenText, null, manualRecurrence, { processing = it }) { pendingConflict = it }
                }
            }

            val micPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) launchSpeechRecognizer(speechLauncher)
                else statusFlow.value = "Se necesita permiso de micrófono para dictar recordatorios"
            }

            val notifPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Recalcula los permisos cada vez que la app vuelve al primer plano
            // (por ejemplo, al volver de la pantalla de ajustes tras concederlos).
            var refreshTrigger by remember { mutableStateOf(0) }
            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTrigger++
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val needsExactAlarm = remember(refreshTrigger) { !canScheduleExactAlarms() }
            val needsBatteryExemption = remember(refreshTrigger) { !isIgnoringBatteryOptimizations() }

            RecordatorioVozTheme {
                MainScreen(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    status = status,
                    processing = processing,
                    activeReminders = activeReminders,
                    historyReminders = historyReminders,
                    textInput = textInput,
                    onTextInputChange = { textInput = it },
                    manualDate = manualDate,
                    manualTime = manualTime,
                    onPickDate = {
                        val base = manualDate ?: LocalDate.now()
                        DatePickerDialog(
                            this@MainActivity,
                            { _, year, month, day -> manualDate = LocalDate.of(year, month + 1, day) },
                            base.year, base.monthValue - 1, base.dayOfMonth
                        ).show()
                    },
                    onPickTime = {
                        val base = manualTime ?: LocalTime.of(9, 0)
                        TimePickerDialog(
                            this@MainActivity,
                            { _, hour, minute -> manualTime = LocalTime.of(hour, minute) },
                            base.hour, base.minute, true
                        ).show()
                    },
                    onClearManualDateTime = { manualDate = null; manualTime = null },
                    manualRecurrence = manualRecurrence,
                    onManualRecurrenceChange = { manualRecurrence = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onExport = { exportBackup(allReminders) },
                    onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    onClearHistory = { showClearHistoryConfirm = true },
                    onSubmitText = {
                        val overrideMillis = if (manualDate != null || manualTime != null) {
                            val date = manualDate ?: LocalDate.now()
                            val time = manualTime ?: LocalTime.of(9, 0)
                            LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } else null
                        processUtterance(textInput, overrideMillis, manualRecurrence, { processing = it }) { pendingConflict = it }
                        textInput = ""
                        manualDate = null
                        manualTime = null
                        manualRecurrence = Recurrence.NONE
                    },
                    needsExactAlarmPermission = needsExactAlarm,
                    needsBatteryExemption = needsBatteryExemption,
                    onFixBatteryExemption = { requestIgnoreBatteryOptimizations() },
                    onMicClick = {
                        val hasMicPermission = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasMicPermission) launchSpeechRecognizer(speechLauncher)
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onFixExactAlarmPermission = { openExactAlarmSettings() },
                    onReminderClick = { selectedReminder = it }
                )

                selectedReminder?.let { reminder ->
                    EditReminderDialog(
                        reminder = reminder,
                        onDismiss = { selectedReminder = null },
                        onSave = { newTitle, newMillis, newRecurrence, newNote ->
                            lifecycleScope.launch {
                                tryCommit(newMillis, reminder.id, { pendingConflict = it }) { millis ->
                                    commitUpdate(reminder, newTitle, millis, newRecurrence, newNote)
                                }
                            }
                            selectedReminder = null
                        },
                        onDelete = {
                            lifecycleScope.launch {
                                AlarmScheduler.cancelAll(this@MainActivity, reminder.id)
                                dao.delete(reminder)
                                statusFlow.value = "Recordatorio eliminado"
                            }
                            selectedReminder = null
                        }
                    )
                }

                pendingConflict?.let { request ->
                    ConflictDialog(
                        request = request,
                        onConfirm = {
                            request.onConfirm()
                            pendingConflict = null
                        },
                        onCancel = { pendingConflict = null },
                        onChangeDateTime = {
                            pendingConflict = null
                            val initialDate = LocalDate.now()
                            DatePickerDialog(
                                this@MainActivity,
                                { _, year, month, day ->
                                    val pickedDate = LocalDate.of(year, month + 1, day)
                                    val initialTime = LocalTime.now()
                                    TimePickerDialog(
                                        this@MainActivity,
                                        { _, hour, minute ->
                                            val newMillis = LocalDateTime.of(pickedDate, LocalTime.of(hour, minute))
                                                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                            request.onReschedule(newMillis)
                                        },
                                        initialTime.hour, initialTime.minute, true
                                    ).show()
                                },
                                initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth
                            ).show()
                        }
                    )
                }

                if (showClearHistoryConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearHistoryConfirm = false },
                        title = { Text("Vaciar historial") },
                        text = { Text("Se eliminarán permanentemente todos los recordatorios ya vencidos (no afecta a los próximos ni a los recurrentes). ¿Continuar?") },
                        confirmButton = {
                            TextButton(onClick = {
                                lifecycleScope.launch {
                                    dao.deleteHistoryBefore(System.currentTimeMillis())
                                    statusFlow.value = "Historial vaciado"
                                }
                                showClearHistoryConfirm = false
                            }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancelar") }
                        }
                    )
                }

                if (userName == null) {
                    NamePromptDialog(
                        onConfirm = { name ->
                            UserPrefs.setName(this@MainActivity, name)
                            userName = name
                        }
                    )
                }
            }
        }
    }

    /**
     * Genera un archivo de respaldo (JSON) con todos los recordatorios y abre
     * el selector para compartirlo por correo, WhatsApp, Drive, etc.
     */
    private fun exportBackup(reminders: List<Reminder>) {
        val json = JSONArray()
        reminders.forEach { reminder ->
            json.put(JSONObject().apply {
                put("title", reminder.title)
                put("triggerAtMillis", reminder.triggerAtMillis)
                put("recurrence", reminder.recurrence)
                put("note", reminder.note ?: JSONObject.NULL)
            })
        }

        val backupsDir = File(cacheDir, "backups").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(backupsDir, "miagenda_backup_$timestamp.json")
        file.writeText(json.toString(2))

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Backup de MiAgenda")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir backup"))
    }

    /**
     * Lee un archivo de respaldo (elegido por el usuario) y restaura los
     * recordatorios: los inserta como nuevos y reprograma las alarmas
     * futuras. No borra lo que ya tenías, solo agrega lo del backup.
     */
    private fun importBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("No se pudo leer el archivo")

                val array = JSONArray(text)
                var count = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val reminder = Reminder(
                        title = obj.getString("title"),
                        triggerAtMillis = obj.getLong("triggerAtMillis"),
                        recurrence = obj.optString("recurrence", Recurrence.NONE),
                        note = if (obj.isNull("note")) null else obj.optString("note")
                    )
                    val id = dao.insert(reminder)
                    if (reminder.recurrence != Recurrence.NONE || reminder.triggerAtMillis > System.currentTimeMillis()) {
                        AlarmScheduler.scheduleAll(this@MainActivity, reminder.copy(id = id))
                    }
                    count++
                }
                statusFlow.value = "Se restauraron $count recordatorios del backup"
            } catch (e: Exception) {
                statusFlow.value = "Error al restaurar el backup: ${e.message}"
            }
        }
    }

    private fun launchSpeechRecognizer(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicta tu recordatorio, ej: 'el lunes reunión con Juan a las 5 pm'")
        }
        statusFlow.value = "Escuchando..."
        launcher.launch(intent)
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("EEE d 'de' MMMM, HH:mm", Locale("es", "ES")).format(millis)

private fun Reminder.matchesSearch(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, ignoreCase = true) || (note?.contains(q, ignoreCase = true) == true)
}

private val dateButtonFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("es", "ES"))
private val timeButtonFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("es", "ES"))

@Composable
private fun NamePromptDialog(onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* Obligatorio: no se puede cerrar sin poner un nombre. */ },
        title = { Text("¡Bienvenido a MiAgenda!") },
        text = {
            Column {
                Text("¿Cómo te llamas? Así la alarma te va a hablar por tu nombre, por ejemplo: \"Ismael, reunión con María\".")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Tu nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text("Continuar") }
        }
    )
}

@Composable
private fun ConflictDialog(
    request: ConflictRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onChangeDateTime: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Posible conflicto de horario") },
        text = {
            Column {
                Text("Ya tienes estos recordatorios cerca de esa hora:")
                Spacer(modifier = Modifier.height(8.dp))
                request.conflicts.forEach { conflict ->
                    Text(
                        "• ${conflict.title} — ${formatDate(conflict.triggerAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("¿Quieres guardarlo de todas formas, cambiar la fecha/hora, o cancelar?")
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onConfirm) { Text("Guardar de todas formas") }
                TextButton(onClick = onChangeDateTime) { Text("Cambiar fecha/hora") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
    )
}

private val createRecurrenceOptions = listOf(
    Recurrence.NONE to "No se repite",
    Recurrence.DAILY to "Cada día",
    Recurrence.WEEKLY to "Cada semana",
    Recurrence.MONTHLY to "Cada mes (mismo número)",
    Recurrence.MONTHLY_BY_WEEKDAY to "Cada mes (mismo día de semana)"
)

private val PendingBackground = Color(0xFFFCE6D2)
private val HistoryBackground = Color(0xFFF9D9D9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    status: String,
    processing: Boolean,
    activeReminders: List<Reminder>,
    historyReminders: List<Reminder>,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    manualDate: LocalDate?,
    manualTime: LocalTime?,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onClearManualDateTime: () -> Unit,
    manualRecurrence: String,
    onManualRecurrenceChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearHistory: () -> Unit,
    onSubmitText: () -> Unit,
    needsExactAlarmPermission: Boolean,
    needsBatteryExemption: Boolean,
    onFixBatteryExemption: () -> Unit,
    onMicClick: () -> Unit,
    onFixExactAlarmPermission: () -> Unit,
    onReminderClick: (Reminder) -> Unit
) {
    val tabTitle = when (selectedTab) {
        1 -> "Pendientes"
        2 -> "Historial"
        3 -> "Backup"
        else -> "MiAgenda"
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                AppAccentColors.indigo,
                                AppAccentColors.indigoDark,
                                AppAccentColors.indigoDeep
                            )
                        )
                    )
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⏰", fontSize = 15.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            tabTitle,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    label = { Text("Crear") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Filled.Alarm, contentDescription = null) },
                    label = { Text("Pendientes") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text("Historial") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { onTabSelected(3) },
                    icon = { Icon(Icons.Filled.Restore, contentDescription = null) },
                    label = { Text("Backup") }
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                val infiniteTransition = rememberInfiniteTransition(label = "fabPulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2400, easing = LinearOutSlowInEasing)
                    ),
                    label = "pulseScale"
                )
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(animation = tween(2400)),
                    label = "pulseAlpha"
                )

                Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .border(2.dp, AppAccentColors.amber.copy(alpha = pulseAlpha), CircleShape)
                    )
                    FloatingActionButton(
                        onClick = onMicClick,
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = Color.White,
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape
                    ) {
                        if (processing) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Icon(Icons.Filled.Mic, contentDescription = "Dictar recordatorio", modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (selectedTab) {
            0 -> CreateTab(
                modifier = Modifier.padding(padding),
                status = status,
                textInput = textInput,
                onTextInputChange = onTextInputChange,
                manualDate = manualDate,
                manualTime = manualTime,
                onPickDate = onPickDate,
                onPickTime = onPickTime,
                onClearManualDateTime = onClearManualDateTime,
                manualRecurrence = manualRecurrence,
                onManualRecurrenceChange = onManualRecurrenceChange,
                onSubmitText = onSubmitText,
                needsExactAlarmPermission = needsExactAlarmPermission,
                needsBatteryExemption = needsBatteryExemption,
                onFixBatteryExemption = onFixBatteryExemption,
                onFixExactAlarmPermission = onFixExactAlarmPermission
            )
            1 -> RemindersListTab(
                modifier = Modifier.padding(padding),
                background = PendingBackground,
                title = "Pendientes",
                emptyMessage = "No tienes recordatorios pendientes.\nCreálos desde la pestaña \"Crear\".",
                reminders = activeReminders,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                headerAction = null,
                cardContent = { reminder -> ReminderCard(reminder = reminder, onClick = { onReminderClick(reminder) }) }
            )
            2 -> RemindersListTab(
                modifier = Modifier.padding(padding),
                background = HistoryBackground,
                title = "Historial",
                emptyMessage = "Todavía no hay recordatorios vencidos.",
                reminders = historyReminders,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                headerAction = {
                    TextButton(onClick = onClearHistory) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Vaciar todo")
                    }
                },
                cardContent = { reminder -> HistoryCard(reminder = reminder, onClick = { onReminderClick(reminder) }) }
            )
            else -> BackupTab(
                modifier = Modifier.padding(padding),
                onExport = onExport,
                onImport = onImport
            )
        }
    }
}

@Composable
private fun CreateTab(
    modifier: Modifier,
    status: String,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    manualDate: LocalDate?,
    manualTime: LocalTime?,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onClearManualDateTime: () -> Unit,
    manualRecurrence: String,
    onManualRecurrenceChange: (String) -> Unit,
    onSubmitText: () -> Unit,
    needsExactAlarmPermission: Boolean,
    needsBatteryExemption: Boolean,
    onFixBatteryExemption: () -> Unit,
    onFixExactAlarmPermission: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        if (needsExactAlarmPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Falta activar permiso de alarmas exactas para que suenen a la hora precisa.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFixExactAlarmPermission) { Text("Activar permiso") }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (needsBatteryExemption) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "El ahorro de batería puede retrasar o cancelar las alarmas. Desactívalo para esta app.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFixBatteryExemption) { Text("Ignorar optimización de batería") }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                status,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = onTextInputChange,
                placeholder = { Text("Escribe tu recordatorio...") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            FilledIconButton(
                onClick = onSubmitText,
                enabled = textInput.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Agregar recordatorio")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPickDate) {
                Text(manualDate?.format(dateButtonFormatter) ?: "Elegir fecha")
            }
            OutlinedButton(onClick = onPickTime) {
                Text(manualTime?.format(timeButtonFormatter) ?: "Elegir hora")
            }
            if (manualDate != null || manualTime != null) {
                TextButton(onClick = onClearManualDateTime) {
                    Text("Quitar")
                }
            }
        }
        Text(
            "Si eliges fecha/hora aquí, escribe solo el título arriba (sin fecha/hora en el texto).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("¿Se repite este recordatorio?", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Toca una opción. La primera vez sonará en la fecha/hora que pongas arriba, y después se repetirá solo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            createRecurrenceOptions.forEach { (value, label) ->
                FilterChip(
                    selected = manualRecurrence == value,
                    onClick = { onManualRecurrenceChange(value) },
                    label = { Text(label) }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            recurrenceExplanation(manualRecurrence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(100.dp))
    }
}

private fun recurrenceExplanation(recurrence: String): String = when (recurrence) {
    Recurrence.DAILY -> "Ejemplo: si lo pones para hoy a las 8:00 am, sonará TODOS los días a las 8:00 am."
    Recurrence.WEEKLY -> "Ejemplo: si lo pones para este viernes a las 5:00 pm, sonará TODOS los viernes a las 5:00 pm."
    Recurrence.MONTHLY -> "Ejemplo: si lo pones para el día 15, sonará el día 15 de CADA mes a la misma hora."
    Recurrence.MONTHLY_BY_WEEKDAY -> "Ejemplo: si lo pones para el primer viernes de este mes, sonará el PRIMER VIERNES de cada mes (aunque caiga en otro número de día)."
    else -> "Sonará una sola vez, en la fecha y hora que indiques."
}

@Composable
private fun BackupTab(
    modifier: Modifier,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "Copia de seguridad",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Guarda tus recordatorios en un archivo para no perderlos si cambias de teléfono o borras la app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("1. Hacer backup", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Crea un archivo con TODOS tus recordatorios y te deja enviarlo por WhatsApp, correo, Drive, o guardarlo donde quieras. Hazlo de vez en cuando, como quien guarda una foto importante.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Hacer backup ahora")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("2. Restaurar backup", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "¿Ya tienes un archivo de backup guardado? Tócalo aquí, elige el archivo, y tus recordatorios vuelven a aparecer con sus alarmas funcionando. No borra los que ya tengas: solo agrega los del archivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Restaurar desde un archivo")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RemindersListTab(
    modifier: Modifier,
    background: Color,
    title: String,
    emptyMessage: String,
    reminders: List<Reminder>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    headerAction: (@Composable () -> Unit)?,
    cardContent: @Composable (Reminder) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            headerAction?.invoke()
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Buscar recordatorios...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Limpiar búsqueda")
                    }
                }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (reminders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Alarm,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    emptyMessage,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(reminders) { reminder ->
                    cardContent(reminder)
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(reminder: Reminder, onClick: () -> Unit) {
    val isRecurring = reminder.recurrence != Recurrence.NONE
    val railColor = if (isRecurring) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val iconContainerColor = if (isRecurring) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val iconTint = if (isRecurring) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(railColor)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isRecurring) Icons.Filled.Repeat else Icons.Filled.Alarm,
                        contentDescription = null,
                        tint = iconTint
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        reminder.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (isRecurring) {
                            "se repite ${recurrenceLabel(reminder.recurrence)} · próx. ${formatDate(reminder.triggerAtMillis)}"
                        } else {
                            formatDate(reminder.triggerAtMillis)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!reminder.note.isNullOrBlank()) {
                        Text(
                            reminder.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Editar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun recurrenceLabel(recurrence: String): String = when (recurrence) {
    Recurrence.DAILY -> "cada día"
    Recurrence.WEEKLY -> "cada semana"
    Recurrence.MONTHLY -> "cada mes"
    Recurrence.MONTHLY_BY_WEEKDAY -> "cada mes (mismo día de semana)"
    else -> ""
}

@Composable
private fun HistoryCard(reminder: Reminder, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        reminder.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Venció " + formatDate(reminder.triggerAtMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Reagendar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Replay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
