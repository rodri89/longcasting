package ar.com.longcasting.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ar.com.longcasting.AppGraph
import ar.com.longcasting.core.measure.formatOfficialCm
import ar.com.longcasting.core.model.DateFilter
import ar.com.longcasting.core.model.RankingEntry
import ar.com.longcasting.core.model.Shooter
import ar.com.longcasting.core.model.Sinker
import ar.com.longcasting.core.model.ThrowSummary
import ar.com.longcasting.core.model.daysWithThrows
import ar.com.longcasting.core.model.range
import ar.com.longcasting.core.model.shortLabel
import ar.com.longcasting.data.decimal
import ar.com.longcasting.data.formatDateTime
import ar.com.longcasting.data.nowMillis
import ar.com.longcasting.data.throwsToCsv
import ar.com.longcasting.ui.ChipSelector
import ar.com.longcasting.ui.InfoPill
import ar.com.longcasting.ui.pluralThrows
import ar.com.longcasting.ui.StatusColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

enum class HistoryTab(val label: String) { TIROS("Tiros"), RANKING("Ranking") }

data class HistoryFilter(
    val shooterId: Long? = null,
    val sinkerId: Long? = null,
    val date: DateFilter = DateFilter.Todo,
)

data class HistoryUiState(
    val throws: List<ThrowSummary> = emptyList(),
    val ranking: List<RankingEntry> = emptyList(),
    val shooters: List<Shooter> = emptyList(),
    val sinkers: List<Sinker> = emptyList(),
    val availableDays: List<LocalDate> = emptyList(),
    val filter: HistoryFilter = HistoryFilter(),
    val tab: HistoryTab = HistoryTab.TIROS,
    val message: String? = null,
) {
    /**
     * El ranking se muestra siempre partido por plomada: comparar un tiro de 175 g contra uno
     * de 100 g no significa nada. La consulta ya viene ordenada por peso y despues por marca,
     * asi que agrupar respeta ese orden.
     */
    val rankingBySinker: List<Pair<String, List<RankingEntry>>>
        get() = ranking.groupBy { it.sinkerLabel }.toList()
}

/**
 * Claves de la lista de Historial. Mismo criterio que en Gente: las dos sub-pestañas comparten
 * un solo LazyColumn, asi que la unicidad hay que garantizarla entre todas las listas y no
 * dentro de cada una.
 */
internal fun rankKey(entry: RankingEntry): String = "rank-${entry.shooterId}-${entry.sinkerId}"

internal fun throwKey(row: ThrowSummary): String = "throw-${row.id}"

internal fun historyKeys(state: HistoryUiState): List<String> =
    state.ranking.map(::rankKey) + state.throws.map(::throwKey)

class HistoryViewModel(private val graph: AppGraph) : ViewModel() {

    private val filter = MutableStateFlow(HistoryFilter())
    private val tab = MutableStateFlow(HistoryTab.TIROS)
    private val message = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val results = filter.flatMapLatest { current ->
        val range = current.date.range(nowMillis())
        combine(
            graph.throws.observeHistory(current.shooterId, current.sinkerId, range = range),
            graph.throws.observeRanking(current.shooterId, current.sinkerId, range),
        ) { throws, ranking -> throws to ranking }
    }

    val state: StateFlow<HistoryUiState> = combine(
        results,
        graph.shooters.observeAll(),
        graph.sinkers.observeAll(),
        graph.throws.observeThrowTimestamps(),
        combine(filter, tab, message) { f, t, m -> Triple(f, t, m) },
    ) { (throws, ranking), shooters, sinkers, timestamps, (currentFilter, currentTab, currentMessage) ->
        HistoryUiState(
            throws = throws,
            ranking = ranking,
            shooters = shooters,
            sinkers = sinkers,
            availableDays = daysWithThrows(timestamps),
            filter = currentFilter,
            tab = currentTab,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun selectTab(value: HistoryTab) {
        tab.value = value
    }

    fun setDate(value: DateFilter) {
        filter.value = filter.value.copy(date = value)
    }

    fun toggleShooter(id: Long) {
        filter.value = filter.value.copy(
            shooterId = if (filter.value.shooterId == id) null else id
        )
    }

    fun toggleSinker(id: Long) {
        filter.value = filter.value.copy(
            sinkerId = if (filter.value.sinkerId == id) null else id
        )
    }

    fun dismissMessage() {
        message.value = null
    }

    fun export() {
        viewModelScope.launch {
            val rows = state.value.throws
            if (rows.isEmpty()) {
                message.value = "No hay tiros para exportar con estos filtros."
                return@launch
            }
            val ok = graph.exporter.share(
                fileName = "longcasting.csv",
                mimeType = "text/csv",
                content = throwsToCsv(rows),
            )
            message.value = if (ok) null else "No se pudo exportar."
        }
    }
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            HistoryTab.entries.forEach { entry ->
                Tab(
                    selected = state.tab == entry,
                    onClick = { viewModel.selectTab(entry) },
                    text = { Text(entry.label, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            item { DateFilterRow(state, viewModel) }
            item {
                ChipSelector(
                    label = "PLOMADA",
                    items = state.sinkers,
                    selected = state.sinkers.firstOrNull { it.id == state.filter.sinkerId },
                    onSelect = { viewModel.toggleSinker(it.id) },
                    labelOf = { it.label },
                )
            }
            item {
                ChipSelector(
                    label = "TIRADOR",
                    items = state.shooters,
                    selected = state.shooters.firstOrNull { it.id == state.filter.shooterId },
                    onSelect = { viewModel.toggleShooter(it.id) },
                    labelOf = { it.name },
                )
            }

            state.message?.let { text ->
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, Modifier.weight(1f), color = StatusColors.warn)
                        OutlinedButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                }
            }

            when (state.tab) {
                HistoryTab.TIROS -> throwsSection(state, viewModel)
                HistoryTab.RANKING -> rankingSection(state)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.throwsSection(
    state: HistoryUiState,
    viewModel: HistoryViewModel,
) {
    item {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pluralThrows(state.throws.size), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = viewModel::export) { Text("Exportar CSV") }
        }
    }
    if (state.throws.isEmpty()) {
        item { EmptyHint("No hay tiros para estos filtros.") }
    }
    items(state.throws, key = ::throwKey) { row -> ThrowRow(row) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rankingSection(state: HistoryUiState) {
    if (state.ranking.isEmpty()) {
        item { EmptyHint("Todavía no hay marcas válidas para estos filtros.") }
        return
    }
    state.rankingBySinker.forEach { (sinkerLabel, entries) ->
        item(key = "rank-header-$sinkerLabel") {
            Text(
                sinkerLabel,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(entries, key = ::rankKey) { entry ->
            RankRow(position = entries.indexOf(entry) + 1, entry = entry)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DateFilterRow(state: HistoryUiState, viewModel: HistoryViewModel) {
    // Solo se ofrecen dias que realmente tienen tiros: no tiene sentido poder elegir un dia
    // vacio, y ademas evita meter un calendario en una pantalla que se usa al sol.
    val options = buildList {
        add(DateFilter.Todo to "Todo")
        add(DateFilter.Hoy to "Hoy")
        add(DateFilter.UltimosSieteDias to "7 días")
        state.availableDays.forEach { day -> add(DateFilter.Dia(day) to day.shortLabel()) }
    }
    ChipSelector(
        label = "FECHA",
        items = options,
        selected = options.firstOrNull { it.first == state.filter.date },
        onSelect = { viewModel.setDate(it.first) },
        labelOf = { it.second },
    )
}

@Composable
private fun RankRow(position: Int, entry: RankingEntry) {
    val podium = position <= 3
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (podium) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(if (podium) podiumColor(position) else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$position",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = if (podium) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(entry.shooterName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pluralThrows(entry.throwCount)} · promedio ${formatOfficialCm(entry.averageCm)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Una marca rapida trae varios metros de error: si encabeza el podio hay que
                // decirlo, no dejar que se lea como si fuera una medicion buena.
                if (entry.bestWasQuick) {
                    Text(
                        "mejor marca tomada en modo rápido",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
            }
            Text(formatOfficialCm(entry.bestCm), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

private fun podiumColor(position: Int): Color = when (position) {
    1 -> Color(0xFFC9A227)
    2 -> Color(0xFF8C8C8C)
    else -> Color(0xFF9A6A3A)
}

@Composable
private fun ThrowRow(row: ThrowSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.shooterName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${row.sinkerLabel} · ${formatDateTime(row.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatOfficialCm(row.officialCm),
                    style = MaterialTheme.typography.headlineSmall,
                    textDecoration = if (row.valid) null else TextDecoration.LineThrough,
                    color = if (row.valid) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        StatusColors.bad
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (row.measuredWithTape) {
                    InfoPill("cinta", StatusColors.good)
                    row.gpsDistanceM?.let { InfoPill("GPS ${decimal(it, 2)} m") }
                } else {
                    if (row.quickMode) InfoPill("rápido", StatusColors.warn)
                    row.gpsSigmaM?.let { InfoPill("± ${decimal(it, 2)} m") }
                }
                row.pathLengthM?.let { InfoPill("caminó ${decimal(it, 0)} m") }
                if (!row.valid) InfoPill(row.invalidReason ?: "inválido", StatusColors.bad)
            }
        }
    }
}
