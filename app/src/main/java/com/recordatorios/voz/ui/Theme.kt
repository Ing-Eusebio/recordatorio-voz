package com.recordatorios.voz.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Indigo = Color(0xFF4F46E5)
private val IndigoDark = Color(0xFF3730A3)
private val IndigoDeep = Color(0xFF241F4D)
private val Teal = Color(0xFF14B8A6)
private val Amber = Color(0xFFF59E0B)
private val AmberDark = Color(0xFFD97F06)
private val AmberContainer = Color(0xFFFCEACB)
private val Background = Color(0xFFF7F7FC)
private val Surface = Color(0xFFFFFFFF)
private val SurfaceVariant = Color(0xFFEEEDF9)
private val OnSurfaceMuted = Color(0xFF6B7280)
private val ErrorRed = Color(0xFFDC2626)

/** Colores fuera del esquema estándar de Material, para el degradado del
 *  app bar, la franja/ícono de "recurrente" y la pantalla de alarma. */
object AppAccentColors {
    val indigo = Indigo
    val indigoDark = IndigoDark
    val indigoDeep = IndigoDeep
    val amber = Amber
    val amberDark = AmberDark
    val alarmRedBright = Color(0xFFB3261E)
    val alarmRedDeep = Color(0xFF3A0F13)
}

private val AppColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = SurfaceVariant,
    onPrimaryContainer = IndigoDark,
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberDark,
    background = Background,
    onBackground = Color(0xFF1F2937),
    surface = Surface,
    onSurface = Color(0xFF1F2937),
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    error = ErrorRed,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = ErrorRed
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontWeight = FontWeight.Medium)
    )
}

@Composable
fun RecordatorioVozTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
