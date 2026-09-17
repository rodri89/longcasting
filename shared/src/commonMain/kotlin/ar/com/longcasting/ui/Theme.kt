package ar.com.longcasting.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta pensada para leerse con sol directo y de pie: contraste alto y un acento naranja
 * que se distingue del verde de la cancha y del azul del cielo.
 */
private val Sand = Color(0xFFF6F3EE)
private val Ink = Color(0xFF141A1F)
private val Ocean = Color(0xFF13566E)
private val OceanLight = Color(0xFF69C6E4)
private val Signal = Color(0xFFE2622B)
private val SignalLight = Color(0xFFFF9A66)
private val Good = Color(0xFF2E7D4F)
private val Bad = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Ocean,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9F3),
    onPrimaryContainer = Color(0xFF00323F),
    secondary = Signal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCB),
    onSecondaryContainer = Color(0xFF3A1200),
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E1DA),
    onSurfaceVariant = Color(0xFF49454A),
    error = Bad,
)

private val DarkColors = darkColorScheme(
    primary = OceanLight,
    onPrimary = Color(0xFF00323F),
    primaryContainer = Color(0xFF0E4658),
    onPrimaryContainer = Color(0xFFCDE9F3),
    secondary = SignalLight,
    onSecondary = Color(0xFF531D00),
    secondaryContainer = Color(0xFF7A3510),
    onSecondaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF11161A),
    onBackground = Color(0xFFE4E2DD),
    surface = Color(0xFF1A2128),
    onSurface = Color(0xFFE4E2DD),
    surfaceVariant = Color(0xFF2A3138),
    onSurfaceVariant = Color(0xFFC8C5BF),
)

/** Color de la barra de calidad y de los avisos de validez. */
object StatusColors {
    val good = Good
    val bad = Bad
    val warn = Signal
}

private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontSize = 72.sp, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        // En cancha el cuerpo se lee de lejos: un escalon mas grande que el estandar.
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** Estilo del numero grande de distancia. Tabular para que no baile al actualizarse. */
val DistanceStyle = TextStyle(
    fontSize = 76.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-2).sp,
)

@Composable
fun LongcastingTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
