package com.recordatorios.voz.parser

import com.recordatorios.voz.data.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

data class ParsedReminder(
    val title: String,
    val triggerAtMillis: Long,
    val recurrence: String = Recurrence.NONE
)

class ParseException(message: String) : Exception(message)

/**
 * Interpreta frases en español dictadas por voz sin usar ninguna API externa.
 * Cubre los casos más comunes (hoy/mañana/pasado mañana, días de la semana,
 * "en N minutos/horas/días", y horas con o sin am/pm). No es tan flexible
 * como un LLM: frases muy ambiguas pueden interpretarse distinto a lo
 * esperado — conviene decir "a las" antes de la hora para evitar errores.
 */
object LocalReminderParser {

    private val weekdays = mapOf(
        "lunes" to DayOfWeek.MONDAY,
        "martes" to DayOfWeek.TUESDAY,
        "miercoles" to DayOfWeek.WEDNESDAY,
        "miércoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY,
        "viernes" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY,
        "sábado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY
    )

    private val fillerPhrases = listOf(
        "recuérdame", "recuerdame", "recordatorio de que", "recordatorio de",
        "recordatorio", "programa una alarma para", "programa una alarma",
        "programa un recordatorio para", "programa un recordatorio",
        "pon una alarma para", "pon una alarma", "pon un recordatorio para",
        "pon un recordatorio", "tengo que", "tengo", "que"
    )

    private val numberWords = mapOf(
        "un" to 1L, "una" to 1L, "uno" to 1L, "dos" to 2L, "tres" to 3L,
        "cuatro" to 4L, "cinco" to 5L, "seis" to 6L, "siete" to 7L, "ocho" to 8L,
        "nueve" to 9L, "diez" to 10L, "once" to 11L, "doce" to 12L, "trece" to 13L,
        "catorce" to 14L, "quince" to 15L, "veinte" to 20L, "treinta" to 30L
    )

    private val relativeRegex = Regex(
        """(?:en|dentro de)\s+(\d+|${numberWords.keys.joinToString("|")})\s+(segundos?|minutos?|horas?|d[ií]as?)"""
    )
    // Acepta "am"/"pm" con o sin puntos y con o sin espacio entre letras
    // (la transcripción de voz a veces mete un espacio: "p. m.").
    private const val MERIDIEM = """a\.?\s?m\.?|p\.?\s?m\.?|de la ma[ñn]ana|de la tarde|de la noche"""

    private val lasRegex = Regex(
        """a\s+las?\s+(\d{1,2})(?::(\d{2}))?\s*($MERIDIEM)?"""
    )
    private val looseTimeRegex = Regex(
        """(\d{1,2})(?::(\d{2}))?\s*($MERIDIEM)"""
    )
    private val strayMeridiemRegex = Regex("""(?i)\b(a\.?\s?m\.?|p\.?\s?m\.?)\b""")

    private val weeklyWithDayRegex = Regex(
        """(?:todos los|cada) (lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bados?|domingos?)"""
    )
    private val dailyRegex = Regex("""todos los d[ií]as|cada d[ií]a|diariamente""")
    private val weeklyRegex = Regex("""cada semana|todas las semanas|semanalmente""")
    private val monthlyRegex = Regex("""cada mes|todos los meses|mensualmente""")

    fun parse(spokenText: String): ParsedReminder {
        val lower = spokenText.lowercase(Locale.getDefault())
        val now = LocalDateTime.now()

        relativeRegex.find(lower)?.let { match ->
            val amountStr = match.groupValues[1]
            val amount = amountStr.toLongOrNull() ?: numberWords[amountStr] ?: 1L
            val unit = match.groupValues[2]
            val target = when {
                unit.startsWith("segundo") -> now.plusSeconds(amount)
                unit.startsWith("minuto") -> now.plusMinutes(amount)
                unit.startsWith("hora") -> now.plusHours(amount)
                else -> now.plusDays(amount)
            }
            val title = cleanTitle(spokenText, match.range)
            return ParsedReminder(title, toMillis(target))
        }

        var targetDate = now.toLocalDate()
        var dateMatchRange: IntRange? = null
        var recurrence = Recurrence.NONE
        var recurrenceMatchRange: IntRange? = null

        // Se revisa primero "todos los lunes"/"cada lunes" para que el día de
        // la semana y la recurrencia se limpien del título como un solo
        // fragmento (evita rangos superpuestos con la detección genérica).
        val weeklyDayMatch = weeklyWithDayRegex.find(lower)
        if (weeklyDayMatch != null) {
            val rawWord = weeklyDayMatch.groupValues[1]
            // Solo sábado/domingo tienen forma plural opcional en el patrón
            // ("sábados", "domingos"); el resto ya termina en "s" siempre.
            val dow = weekdays[rawWord] ?: weekdays[rawWord.removeSuffix("s")]
            if (dow != null) {
                targetDate = nextOrSameWeekday(now.toLocalDate(), dow)
                dateMatchRange = weeklyDayMatch.range
                recurrence = Recurrence.WEEKLY
                recurrenceMatchRange = weeklyDayMatch.range
            }
        }

        if (recurrence == Recurrence.NONE) {
            dailyRegex.find(lower)?.let {
                recurrence = Recurrence.DAILY
                recurrenceMatchRange = it.range
            }
        }
        if (recurrence == Recurrence.NONE) {
            weeklyRegex.find(lower)?.let {
                recurrence = Recurrence.WEEKLY
                recurrenceMatchRange = it.range
            }
        }
        if (recurrence == Recurrence.NONE) {
            monthlyRegex.find(lower)?.let {
                recurrence = Recurrence.MONTHLY
                recurrenceMatchRange = it.range
            }
        }

        if (dateMatchRange == null) {
            when {
                lower.contains("pasado mañana") || lower.contains("pasado manana") -> {
                    targetDate = now.toLocalDate().plusDays(2)
                    dateMatchRange = findRange(lower, "pasado mañana") ?: findRange(lower, "pasado manana")
                }
                lower.contains("mañana") || lower.contains("manana") -> {
                    targetDate = now.toLocalDate().plusDays(1)
                    dateMatchRange = findRange(lower, "mañana") ?: findRange(lower, "manana")
                }
                lower.contains("hoy") -> {
                    dateMatchRange = findRange(lower, "hoy")
                }
                else -> {
                    for ((word, dow) in weekdays) {
                        if (lower.contains(word)) {
                            targetDate = nextOrSameWeekday(now.toLocalDate(), dow)
                            dateMatchRange = findRange(lower, word)
                            break
                        }
                    }
                }
            }
        }

        var targetTime = LocalTime.of(9, 0)
        var timeMatchRange: IntRange? = null

        val chosenMatch = lasRegex.find(lower) ?: looseTimeRegex.find(lower)
        if (chosenMatch != null) {
            var hour = chosenMatch.groupValues[1].toInt().coerceIn(0, 23)
            val minute = chosenMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val meridiem = chosenMatch.groupValues.getOrNull(3) ?: ""

            val isPm = meridiem.trim().startsWith("p") || meridiem.contains("tarde") || meridiem.contains("noche")
            val isAm = meridiem.trim().startsWith("a") || meridiem.contains("mañana") || meridiem.contains("manana")

            hour = when {
                isPm -> if (hour < 12) hour + 12 else hour
                isAm -> if (hour == 12) 0 else hour
                else ->
                    // Sin am/pm explícito: asumimos PM para horas de 1 a 7,
                    // que es el rango más común en recordatorios cotidianos.
                    if (hour in 1..7) hour + 12 else hour
            }
            targetTime = LocalTime.of(hour % 24, minute)
            timeMatchRange = chosenMatch.range
        }

        var target = LocalDateTime.of(targetDate, targetTime)

        if (dateMatchRange == null && target.isBefore(now)) {
            target = target.plusDays(1)
        }

        val title = cleanTitle(spokenText, dateMatchRange, timeMatchRange, recurrenceMatchRange)
        return ParsedReminder(title, toMillis(target), recurrence)
    }

    private fun nextOrSameWeekday(from: LocalDate, target: DayOfWeek): LocalDate {
        if (from.dayOfWeek == target) return from
        var date = from
        while (date.dayOfWeek != target) date = date.plusDays(1)
        return date
    }

    /**
     * Limpia solo el título de una frase, sin intentar interpretar fecha/hora.
     * Se usa cuando el usuario elige la fecha/hora con un selector manual en
     * vez de dictarla o escribirla dentro del texto.
     */
    fun titleOnly(text: String): String = cleanTitle(text)

    private fun findRange(text: String, needle: String): IntRange? {
        val idx = text.indexOf(needle)
        return if (idx >= 0) idx until (idx + needle.length) else null
    }

    private fun cleanTitle(original: String, vararg ranges: IntRange?): String {
        val sb = StringBuilder(original)
        ranges.filterNotNull().distinct().sortedByDescending { it.first }.forEach { range ->
            if (range.last < sb.length) sb.delete(range.first, range.last + 1)
        }
        var title = sb.toString()

        fillerPhrases.sortedByDescending { it.length }.forEach { phrase ->
            title = title.replace(Regex("(?i)\\b${Regex.escape(phrase)}\\b"), "")
        }

        // Red de seguridad: si algún "am"/"pm"/"a.m."/"p.m." sobrevivió porque
        // no coincidió exactamente con el patrón de hora, lo quitamos igual.
        title = title.replace(strayMeridiemRegex, "")

        title = title.replace(Regex("""\s+"""), " ").trim().trim(',', '.', ' ')
        if (title.isEmpty()) title = "Recordatorio"
        return title.replaceFirstChar { it.uppercase() }
    }

    private fun toMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
