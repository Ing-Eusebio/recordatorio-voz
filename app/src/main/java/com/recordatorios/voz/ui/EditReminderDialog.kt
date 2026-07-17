package com.recordatorios.voz.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.recordatorios.voz.data.Recurrence
import com.recordatorios.voz.data.Reminder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val recurrenceOptions = listOf(
    Recurrence.NONE to "No se repite",
    Recurrence.DAILY to "Cada día",
    Recurrence.WEEKLY to "Cada semana",
    Recurrence.MONTHLY to "Cada mes (mismo número)",
    Recurrence.MONTHLY_BY_WEEKDAY to "Cada mes (mismo día de semana)"
)

@Composable
fun EditReminderDialog(
    reminder: Reminder,
    onDismiss: () -> Unit,
    onSave: (title: String, triggerAtMillis: Long, recurrence: String, note: String?) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(reminder.title) }
    var triggerAtMillis by remember { mutableStateOf(reminder.triggerAtMillis) }
    var recurrence by remember { mutableStateOf(reminder.recurrence) }
    var note by remember { mutableStateOf(reminder.note ?: "") }

    val dateTimeFormat = remember { SimpleDateFormat("EEE d 'de' MMMM, HH:mm", Locale("es", "ES")) }

    fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                triggerAtMillis = cal.timeInMillis
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun openTimePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                cal.set(Calendar.MINUTE, minute)
                triggerAtMillis = cal.timeInMillis
            },
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar recordatorio") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(dateTimeFormat.format(triggerAtMillis), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openDatePicker() }) { Text("Cambiar fecha") }
                    OutlinedButton(onClick = { openTimePicker() }) { Text("Cambiar hora") }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Repetir", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recurrenceOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = recurrence == value,
                            onClick = { recurrence = value },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title, triggerAtMillis, recurrence, note.ifBlank { null })
            }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        }
    )
}
