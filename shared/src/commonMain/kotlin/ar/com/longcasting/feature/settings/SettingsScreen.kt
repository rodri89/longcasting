package ar.com.longcasting.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ar.com.longcasting.AppGraph
import ar.com.longcasting.core.gnss.GnssQuality
import ar.com.longcasting.data.AppSettings
import ar.com.longcasting.data.decimal
import ar.com.longcasting.ui.QualityBar
import ar.com.longcasting.ui.StatTile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val quality: GnssQuality = GnssQuality(),
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        graph.settings.observe(),
        graph.locationEngine.quality,
    ) { settings, quality -> SettingsUiState(settings, quality) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setOccupationSeconds(seconds: Int) {
        viewModelScope.launch { graph.settings.setOccupationSeconds(seconds) }
    }

    fun setMaxAccuracy(meters: Double) {
        viewModelScope.launch { graph.settings.setMaxAccuracy(meters) }
    }

    fun setTargetSigma(meters: Double) {
        viewModelScope.launch { graph.settings.setTargetSigma(meters) }
    }

    fun setQuickMode(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setQuickMode(enabled) }
    }

    fun setHaptic(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setHapticFeedback(enabled) }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModeCard(state, viewModel)

        Text(
            "Diagnóstico GNSS",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        QualityBar(state.quality)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Satélites",
                state.quality.satsUsed?.let { used ->
                    "$used" + (state.quality.satsVisible?.let { " / $it" } ?: "")
                } ?: "—",
                Modifier.weight(1f),
                hint = "usados / visibles",
            )
            StatTile(
                "Doble frecuencia",
                if (state.quality.hasL5) "Sí (L5)" else "No",
                Modifier.weight(1f),
                hint = "techo de precisión",
            )
        }
        Text(
            if (state.quality.hasL5) {
                "Este equipo sigue señales L5. Es lo mejor que puede dar un teléfono: " +
                    "esperá entre 0,3 y 1 m de incertidumbre en un tiro de 200 m."
            } else {
                "Este equipo solo sigue L1. Va a andar, pero con más ruido: contá con " +
                    "entre 1 y 2 m de incertidumbre en un tiro de 200 m."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "Medición",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )

        SettingCard(
            title = "Precisión buscada",
            value = "± ${decimal(state.settings.targetSigmaM, 2)} m",
            description = "La toma se cierra sola apenas la incertidumbre baja de acá, sin " +
                "esperar el tiempo completo. Bajarlo da mediciones mejores pero más lentas, y " +
                "si la señal no alcanza el objetivo se agota el máximo igual. Como el tiro " +
                "combina dos puntos, la incertidumbre final es cerca de 1,4 veces este valor.",
        ) {
            Slider(
                value = state.settings.targetSigmaM.toFloat(),
                onValueChange = { viewModel.setTargetSigma(it.toDouble()) },
                valueRange = 0.3f..5f,
            )
        }

        SettingCard(
            title = "Tiempo máximo",
            value = "${state.settings.occupationSeconds} s",
            description = "Tope de espera en cada extremo cuando no se llega a la precisión " +
                "buscada. Más tiempo baja la incertidumbre, pero con rendimiento decreciente: " +
                "el ruido del GPS está correlacionado y duplicar el tiempo no divide el error " +
                "por dos.",
        ) {
            Slider(
                value = state.settings.occupationSeconds.toFloat(),
                onValueChange = { viewModel.setOccupationSeconds(it.roundToInt()) },
                valueRange = AppSettings.MIN_OCCUPATION_SECONDS.toFloat()..
                    AppSettings.MAX_OCCUPATION_SECONDS.toFloat(),
            )
        }

        SettingCard(
            title = "Precisión mínima aceptable",
            value = "± ${decimal(state.settings.maxAccuracyM, 0)} m",
            description = "Los fixes con error declarado peor que esto se descartan. Bajarlo " +
                "mejora la calidad pero puede dejarte sin muestras con mala señal.",
        ) {
            Slider(
                value = state.settings.maxAccuracyM.toFloat(),
                onValueChange = { viewModel.setMaxAccuracy(it.toDouble()) },
                valueRange = 3f..30f,
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Aviso al terminar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vibra y suena cuando se cierra una ocupación, así no tenés que mirar " +
                            "la pantalla mientras estás agachado sobre la plomada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.hapticFeedback,
                    onCheckedChange = viewModel::setHaptic,
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Sobre la precisión", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Esta app mide con el GNSS del teléfono. La incertidumbre que muestra es " +
                        "de repetibilidad: cuánto se movió la solución durante la toma. La " +
                        "exactitud real se mide en cancha, con la prueba de cierre (volver al " +
                        "vértice y re-ocupar) o comparando contra una cinta. Para una marca " +
                        "oficial usá la cinta y cargala en el tiro.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Selector de modo, arriba de todo porque cambia el significado de todo lo que viene abajo:
 * en rapido no se promedia nada y los umbrales de precision no se usan.
 */
@Composable
private fun ModeCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val quick = state.settings.quickMode
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (quick) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Modo de medición", style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !quick,
                    onClick = { viewModel.setQuickMode(false) },
                    label = { Text("Preciso", style = MaterialTheme.typography.labelLarge) },
                )
                FilterChip(
                    selected = quick,
                    onClick = { viewModel.setQuickMode(true) },
                    label = { Text("Rápido", style = MaterialTheme.typography.labelLarge) },
                )
            }
            Text(
                if (quick) {
                    "Un solo fix por punto, sin promediar: instantáneo, con unos ± 7 m de " +
                        "error en el tiro. Sirve para un número aproximado, no para una marca. " +
                        "Los tiros quedan marcados como rápidos en el historial y el ranking, " +
                        "y los ajustes de abajo no se aplican."
                } else {
                    "Promedia el punto hasta alcanzar la precisión buscada. Es lo que hace que " +
                        "un tiro se pueda comparar contra otro."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
                color = if (quick) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    value: String,
    description: String,
    control: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
            control()
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
