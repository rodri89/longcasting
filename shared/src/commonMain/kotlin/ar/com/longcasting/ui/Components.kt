package ar.com.longcasting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ar.com.longcasting.core.gnss.GnssQuality
import ar.com.longcasting.core.gnss.QualityLevel
import ar.com.longcasting.data.decimal

/** El numero que el tirador mira de lejos. */
@Composable
fun BigDistance(
    meters: Double,
    modifier: Modifier = Modifier,
    caption: String? = null,
    uncertaintyM: Double? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = decimal(meters, 2),
            style = DistanceStyle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("metros", style = MaterialTheme.typography.titleMedium)
            if (uncertaintyM != null) {
                Text(
                    "  ± ${decimal(uncertaintyM, 2)} m",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BigActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(84.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Barra de calidad del receptor, mas el detalle de satelites y doble frecuencia. */
@Composable
fun QualityBar(quality: GnssQuality, modifier: Modifier = Modifier) {
    val (color, label) = when (quality.level) {
        QualityLevel.EXCELLENT -> StatusColors.good to "Señal excelente"
        QualityLevel.GOOD -> StatusColors.good to "Señal buena"
        QualityLevel.FAIR -> StatusColors.warn to "Señal aceptable"
        QualityLevel.POOR -> StatusColors.bad to "Señal pobre"
        QualityLevel.NO_FIX -> MaterialTheme.colorScheme.onSurfaceVariant to "Sin señal"
    }
    val filled = when (quality.level) {
        QualityLevel.EXCELLENT -> 1f
        QualityLevel.GOOD -> 0.75f
        QualityLevel.FAIR -> 0.5f
        QualityLevel.POOR -> 0.25f
        QualityLevel.NO_FIX -> 0.05f
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    quality.accuracyM?.let { "± ${decimal(it, 1)} m" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(Modifier.fillMaxWidth(filled).height(8.dp).background(color))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                quality.satsUsed?.let { used ->
                    InfoPill("$used sat" + (quality.satsVisible?.let { "/$it" } ?: ""))
                }
                quality.meanCn0DbHz?.let { InfoPill("${decimal(it, 0)} dB-Hz") }
                if (quality.hasL5) InfoPill("L5", StatusColors.good)
            }
            quality.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusColors.bad,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** "1 tiro" / "3 tiros": el plural se nota enseguida en una pantalla que se lee de reojo. */
fun pluralThrows(count: Int): String = if (count == 1) "1 tiro" else "$count tiros"

@Composable
fun InfoPill(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Fila de chips para elegir tirador o plomada de un toque. */
@Composable
fun <T> ChipSelector(
    label: String,
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    emptyHint: String = "Nada cargado todavia",
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (items.isEmpty()) {
            Text(emptyHint, style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                items(items) { item ->
                    FilterChip(
                        selected = item == selected,
                        onClick = { onSelect(item) },
                        label = { Text(labelOf(item), style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }
    }
}

@Composable
fun StatTile(title: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun Spacer8() = Box(Modifier.height(8.dp).width(8.dp))
