package ar.com.longcasting.feature.rosters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ar.com.longcasting.AppGraph
import ar.com.longcasting.data.AddOutcome
import ar.com.longcasting.core.model.Shooter
import ar.com.longcasting.core.model.Sinker
import ar.com.longcasting.data.decimal
import ar.com.longcasting.ui.StatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RostersUiState(
    val shooters: List<Shooter> = emptyList(),
    val sinkers: List<Sinker> = emptyList(),
    val message: String? = null,
)

/**
 * Claves de la lista de Gente.
 *
 * Viven juntas y no dentro de cada `items` porque la unicidad es una propiedad de **toda** la
 * lista: tiradores y plomadas comparten el mismo LazyColumn y sus ids arrancan los dos en 1,
 * asi que sin prefijo el tirador 1 y la plomada 1 dan la misma clave y Compose aborta con
 * "Key 1 was already used". [rosterKeys] existe para que un test pueda verificar la propiedad
 * completa en vez de mirar cada bloque por separado.
 */
internal fun shooterKey(shooter: Shooter): String = "shooter-${shooter.id}"

internal fun sinkerKey(sinker: Sinker): String = "sinker-${sinker.id}"

internal fun rosterKeys(state: RostersUiState): List<String> =
    state.shooters.map(::shooterKey) + state.sinkers.map(::sinkerKey)

class RostersViewModel(private val graph: AppGraph) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<RostersUiState> = combine(
        graph.shooters.observeAll(),
        graph.sinkers.observeAll(),
        message,
    ) { shooters, sinkers, currentMessage -> RostersUiState(shooters, sinkers, currentMessage) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RostersUiState())

    fun dismissMessage() {
        message.value = null
    }

    fun addShooter(name: String) {
        viewModelScope.launch {
            val cleanName = name.trim()
            message.value = when (graph.shooters.add(cleanName)) {
                is AddOutcome.Created -> null
                is AddOutcome.Restored ->
                    "\"$cleanName\" ya estaba cargado y lo habías quitado. Volvió a la lista con sus tiros."
                is AddOutcome.Duplicate -> "Ya hay un tirador que se llama \"$cleanName\"."
                AddOutcome.Invalid -> "Poné un nombre."
            }
        }
    }

    fun archiveShooter(id: Long) {
        viewModelScope.launch { graph.shooters.archive(id) }
    }

    fun addSinker(grams: Long, minLineMm: Double?) {
        viewModelScope.launch {
            message.value = when (graph.sinkers.add(grams, "$grams g", minLineMm)) {
                is AddOutcome.Created -> null
                is AddOutcome.Restored ->
                    "La plomada de $grams g ya estaba cargada y la habías quitado. Volvió a la lista."
                is AddOutcome.Duplicate -> "Ya hay una plomada de $grams g."
                AddOutcome.Invalid -> "El peso tiene que ser mayor que cero."
            }
        }
    }

    fun archiveSinker(id: Long) {
        viewModelScope.launch { graph.sinkers.archive(id) }
    }
}

@Composable
fun RostersScreen(viewModel: RostersViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newShooter by remember { mutableStateOf("") }
    var newGrams by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { text ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, Modifier.weight(1f).padding(vertical = 10.dp))
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                }
            }
        }

        item { Text("Tiradores", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newShooter,
                    onValueChange = { newShooter = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        viewModel.addShooter(newShooter)
                        newShooter = ""
                    },
                    enabled = newShooter.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) { Text("Agregar") }
            }
        }
        items(state.shooters, key = ::shooterKey) { shooter ->
            RowCard(
                title = shooter.name,
                subtitle = null,
                onRemove = { viewModel.archiveShooter(shooter.id) },
            )
        }

        item {
            Text(
                "Plomadas",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        item {
            Text(
                "Vienen cargadas las cuatro del reglamento con su diámetro mínimo de hilo. " +
                    "Podés agregar otras si entrenás con pesos distintos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newGrams,
                    onValueChange = { text -> newGrams = text.filter { it.isDigit() } },
                    label = { Text("Gramos") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        newGrams.toLongOrNull()?.let { viewModel.addSinker(it, null) }
                        newGrams = ""
                    },
                    enabled = newGrams.toLongOrNull() != null,
                    modifier = Modifier.height(56.dp),
                ) { Text("Agregar") }
            }
        }
        items(state.sinkers, key = ::sinkerKey) { sinker ->
            RowCard(
                title = sinker.label,
                subtitle = sinker.minLineMm?.let { "hilo mínimo ${decimal(it, 2)} mm" },
                onRemove = { viewModel.archiveSinker(sinker.id) },
            )
        }
    }
}

@Composable
private fun RowCard(title: String, subtitle: String?, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Se archiva, no se borra: los tiros ya registrados tienen que seguir teniendo dueño.
            TextButton(onClick = onRemove) { Text("Quitar", color = StatusColors.bad) }
        }
    }
}
