package com.turnocontrol.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.turnocontrol.domain.DayKind

/** Semantic colors Material 3 has no role for: vacation, sick leave and the neutral "free" marker. */
data class TurnoColors(
    val vacation: Color, val onVacation: Color, val vacationContainer: Color,
    val sick: Color, val onSick: Color, val sickContainer: Color,
    val free: Color,
)

private val lightTurno = TurnoColors(
    vacation = Color(0xFF8A6100), onVacation = Color.White, vacationContainer = Color(0xFFFFE08A),
    sick = Color(0xFFA8324A), onSick = Color.White, sickContainer = Color(0xFFFFD9DF),
    free = Color(0xFF6F7A8A),
)
private val darkTurno = TurnoColors(
    vacation = Color(0xFFFFD36A), onVacation = Color(0xFF3F2E00), vacationContainer = Color(0xFF5C4300),
    sick = Color(0xFFFFB1C0), onSick = Color(0xFF4A0D1E), sickContainer = Color(0xFF7A2238),
    free = Color(0xFF9AA5B5),
)

val LocalTurnoColors = staticCompositionLocalOf { lightTurno }
val MaterialTheme.turno: TurnoColors @Composable @ReadOnlyComposable get() = LocalTurnoColors.current

private val lightScheme = lightColorScheme(
    primary = Color(0xFF1F5DD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF), onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF52607A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF0F1C33),
    tertiary = Color(0xFFB4541B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBC9), onTertiaryContainer = Color(0xFF3B1500),
    background = Color(0xFFF8F9FD), surface = Color(0xFFF8F9FD),
    onBackground = Color(0xFF191C21), onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFE1E5F0), onSurfaceVariant = Color(0xFF44485A),
    outline = Color(0xFF747A8C), outlineVariant = Color(0xFFC5C9D8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F4FA),
    surfaceContainer = Color(0xFFECEEF5), surfaceContainerHigh = Color(0xFFE6E9F0), surfaceContainerHighest = Color(0xFFE0E3EA),
)
private val darkScheme = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF0B2F6E),
    primaryContainer = Color(0xFF2A4C8F), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFBBC6E4), onSecondary = Color(0xFF25314A),
    secondaryContainer = Color(0xFF3B4862), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFFFB68B), onTertiary = Color(0xFF522300),
    tertiaryContainer = Color(0xFF6E3410), onTertiaryContainer = Color(0xFFFFDBC9),
    background = Color(0xFF111319), surface = Color(0xFF111319),
    onBackground = Color(0xFFE2E2EA), onSurface = Color(0xFFE2E2EA),
    surfaceVariant = Color(0xFF44485A), onSurfaceVariant = Color(0xFFC5C9D8),
    outline = Color(0xFF8E93A6), outlineVariant = Color(0xFF44485A),
    surfaceContainerLowest = Color(0xFF0C0E14), surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF1D2026), surfaceContainerHigh = Color(0xFF282A31), surfaceContainerHighest = Color(0xFF32353C),
)

@Composable
fun TurnoTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTurnoColors provides if (dark) darkTurno else lightTurno) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme else lightScheme,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
            content = content,
        )
    }
}

fun DayKind.tint(colors: ColorScheme, turno: TurnoColors): Color = when (this) {
    DayKind.WORK -> colors.primary; DayKind.FREE -> turno.free; DayKind.VACATION -> turno.vacation
    DayKind.SICK_LEAVE -> turno.sick; DayKind.UNKNOWN -> colors.onSurfaceVariant
}

fun DayKind.icon(): ImageVector? = when (this) {
    DayKind.WORK -> TurnoIcons.Work; DayKind.FREE -> TurnoIcons.Free; DayKind.VACATION -> TurnoIcons.Vacation
    DayKind.SICK_LEAVE -> TurnoIcons.Medical; DayKind.UNKNOWN -> null
}
