package ar.com.longcasting.feature.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ar.com.longcasting.core.measure.LiveState
import ar.com.longcasting.core.measure.formatOfficialCm
import ar.com.longcasting.data.SessionOrigin
import ar.com.longcasting.data.decimal
import ar.com.longcasting.data.formatDateTime
import ar.com.longcasting.ui.BigActionButton
import ar.com.longcasting.ui.BigDistance
import ar.com.longcasting.ui.ChipSelector
import ar.com.longcasting.ui.QualityBar
import ar.com.longcasting.ui.StatTile
import ar.com.longcasting.ui.StatusColors

@Composable
fun MeasureScreen(viewModel: MeasureViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QualityBar(state.quality)

        when (val stage = state.stage) {
            MeasureStage.Idle -> IdleSection(state, viewModel)
            is MeasureStage.Occupying -> OccupyingSection(stage.progress, viewModel)
            is MeasureStage.Walking -> WalkingSection(stage.live, viewModel)
            is MeasureStage.Result -> ResultSection(stage.value, viewModel)
        }

        state.message?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun IdleSection(state: MeasureUiState, viewModel: MeasureViewModel) {
    val origin = state.origin

    if (origin != null) {
        OriginCard(state, origin, viewModel)
    }

    QuickModeNotice(state)

    ChipSelector(
        label = "TIRADOR",
        items = state.shooters,
        selected = state.selectedShooter,
        onSelect = { viewModel.selectShooter(it.id) },
        labelOf = { it.name },
        emptyHint = "Cargá un tirador en la pestaña Gente",
    )
    ChipSelector(
        label = "PLOMADA",
        items = state.sinkers,
        selected = state.selectedSinker,
        onSelect = { viewModel.selectSinker(it.id) },
        labelOf = { it.label },
    )
    Spacer(Modifier.height(8.dp))

    if (origin == null) {
        BigActionButton(
            text = if (state.quickMode) "MARCAR ORIGEN RÁPIDO" else "MARCAR ORIGEN",
            onClick = viewModel::markOrigin,
            enabled = state.canStart && state.quality.preciseLocationGranted,
        )
        Text(
            if (state.quickMode) {
                "Parate en el vértice y apretá: toma un fix y listo. Ojo que todos los tiros " +
                    "de la jornada arrastran el error de este punto."
            } else {
                "Parate en el vértice, quedate quieto y apretá. Promedia hasta " +
                    "${state.settings.occupationSeconds} s, pero corta antes apenas la " +
                    "precisión llega a la meta. Se hace una sola vez por jornada."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        BigActionButton(
            text = if (state.quickMode) "EMPEZAR TIRO RÁPIDO" else "EMPEZAR TIRO",
            onClick = viewModel::startThrow,
            enabled = state.canStart && state.quality.preciseLocationGranted,
        )
        Text(
            "Tirá primero y apretá acá parado en el vértice: desde ese momento se mide lo " +
                "que caminás hasta la plomada." +
                if (state.quickMode) " Al llegar, la caída se toma de un fix." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Aviso de modo rapido. El selector vive en Ajustes, pero el modo se persiste, asi que si no
 * se avisara aca podrias medir una jornada entera con varios metros de error sin enterarte.
 */
@Composable
private fun QuickModeNotice(state: MeasureUiState) {
    if (!state.quickMode) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Modo rápido activo", style = MaterialTheme.typography.titleMedium)
            Text(
                "Un solo fix por punto: unos ± ${decimal(quickThrowError(state), 0)} m de error " +
                    "en el tiro. Se desactiva en Ajustes.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Error esperable de un tiro rápido: los dos extremos suman en cuadratura. */
internal fun quickThrowError(state: MeasureUiState): Double =
    (state.quality.accuracyM ?: 5.0) * 1.414

@Composable
private fun OriginCard(
    state: MeasureUiState,
    origin: SessionOrigin,
    viewModel: MeasureViewModel,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.originIsFar) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Origen de la jornada", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "marcado ${formatDateTime(origin.markedAt)} · " +
                            "± ${decimal(origin.point.sigmaM, 2)} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    state.distanceToOriginM?.let { "a ${decimal(it, 1)} m" } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.originIsFar) StatusColors.bad else StatusColors.good,
                )
            }

            if (state.originIsFar) {
                Text(
                    "Estás lejos del origen guardado. Si cambiaste de cancha, marcalo de " +
                        "nuevo: si no, todos los tiros van a medirse contra el vértice viejo.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            OutlinedButton(
                onClick = viewModel::markOrigin,
                enabled = state.quality.preciseLocationGranted,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
            ) { Text("Cambiar origen") }
        }
    }
}

@Composable
private fun OccupyingSection(progress: OccupationProgress, viewModel: MeasureViewModel) {
    val title = when (progress.role) {
        PointRole.ORIGIN -> "Fijando el origen"
        PointRole.LANDING -> "Fijando la caída"
        PointRole.CLOSURE -> "Verificando el cierre"
    }
    Text(title, style = MaterialTheme.typography.headlineMedium)

    if (progress.quick) {
        Text(
            "Modo rápido: esperando un fix utilizable.",
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth().height(12.dp))
        OutlinedButton(
            onClick = viewModel::cancelOccupation,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Cancelar") }
        return
    }

    Text(
        "No te muevas. Sostené el teléfono quieto y con el cielo despejado.",
        style = MaterialTheme.typography.bodyLarge,
    )

    val fraction = (progress.elapsedSec.toFloat() / progress.maxSec).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(12.dp),
        color = if (progress.targetReached) StatusColors.good else ProgressIndicatorDefaults.linearColor,
    )
    Text(
        if (progress.targetReached) {
            "Precisión alcanzada, cerrando"
        } else {
            "${progress.elapsedSec} s · hasta ${progress.maxSec} s"
        },
        style = MaterialTheme.typography.titleLarge,
        color = if (progress.targetReached) StatusColors.good else MaterialTheme.colorScheme.onBackground,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Aceptadas", progress.accepted.toString(), Modifier.weight(1f))
        StatTile("Descartadas", progress.rejected.toString(), Modifier.weight(1f))
        StatTile(
            "Incertidumbre",
            progress.sigmaM?.let { "± ${decimal(it, 2)} m" } ?: "—",
            Modifier.weight(1f),
            hint = "meta ± ${decimal(progress.targetSigmaM, 2)} m",
        )
    }
    Text(
        "No espera el tiempo completo: corta sola apenas la incertidumbre baja de " +
            "± ${decimal(progress.targetSigmaM, 2)} m. Si la señal no da, agota el máximo.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = viewModel::cancelOccupation,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text("Cancelar") }
        OutlinedButton(
            onClick = viewModel::finishOccupationEarly,
            enabled = progress.canFinishEarly,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text("Cerrar ya") }
    }
}

@Composable
private fun WalkingSection(live: LiveState, viewModel: MeasureViewModel) {
    BigDistance(
        meters = live.smoothedStraightLineM,
        modifier = Modifier.fillMaxWidth(),
        caption = "en línea recta contra el origen",
        uncertaintyM = live.accuracyM,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            "Recorrido",
            "${decimal(live.pathLengthM, 0)} m",
            Modifier.weight(1f),
            hint = "distancia caminada",
        )
        StatTile(
            "Rumbo",
            live.bearingDeg?.let { "${decimal(it, 0)}°" } ?: "—",
            Modifier.weight(1f),
            hint = "desde el norte",
        )
    }
    Spacer(Modifier.height(4.dp))
    BigActionButton(
        text = "FIJAR CAÍDA",
        onClick = viewModel::markLanding,
        containerColor = MaterialTheme.colorScheme.secondary,
    )
    OutlinedButton(
        onClick = viewModel::reset,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Descartar el tiro") }
}

@Composable
private fun ResultSection(result: ThrowResult, viewModel: MeasureViewModel) {
    var tapeText by remember { mutableStateOf("") }

    BigDistance(
        meters = result.officialCm / 100.0,
        modifier = Modifier.fillMaxWidth(),
        caption = "medida oficial: ${formatOfficialCm(result.officialCm)}",
        uncertaintyM = result.sigmaM,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            "Recorrido",
            "${decimal(result.pathLengthM, 0)} m",
            Modifier.weight(1f),
        )
        StatTile(
            "Rumbo",
            result.bearingDeg?.let { "${decimal(it, 0)}°" } ?: "—",
            Modifier.weight(1f),
        )
        StatTile(
            "Muestras",
            "${result.origin.samplesAccepted}+${result.landing.samplesAccepted}",
            Modifier.weight(1f),
        )
    }

    result.closureErrorM?.let { error ->
        StatTile(
            "Error de cierre",
            "${decimal(error, 2)} m",
            Modifier.fillMaxWidth(),
            hint = "diferencia real al volver al vértice",
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Medida con cinta", style = MaterialTheme.typography.titleMedium)
            Text(
                "Si la tomaste con cinta o estación total, cargala acá: pasa a ser la medida " +
                    "oficial del tiro y la del GPS queda guardada al lado para comparar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = tapeText,
                    onValueChange = { text -> tapeText = text.filter { it.isDigit() } },
                    label = { Text("centímetros") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { viewModel.setTapeDistance(tapeText.toIntOrNull()) },
                    enabled = tapeText.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) { Text("Guardar") }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = viewModel::verifyClosure,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text("Verificar cierre") }
        OutlinedButton(
            onClick = { viewModel.markInvalid("Marcado en cancha") },
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text("Invalidar") }
    }
    Text(
        "Verificar cierre: volvé al vértice y re-ocupá. La diferencia es el error real de " +
            "hoy con este teléfono en esta cancha.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    BigActionButton(text = "SIGUIENTE TIRO", onClick = viewModel::reset)
    TextButton(
        onClick = viewModel::discardThrow,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Borrar este tiro", color = StatusColors.bad) }
}
