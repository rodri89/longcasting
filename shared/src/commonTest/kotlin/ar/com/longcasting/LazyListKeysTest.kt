package ar.com.longcasting

import ar.com.longcasting.core.model.RankingEntry
import ar.com.longcasting.core.model.Shooter
import ar.com.longcasting.core.model.Sinker
import ar.com.longcasting.core.model.ThrowSummary
import ar.com.longcasting.feature.history.HistoryUiState
import ar.com.longcasting.feature.history.historyKeys
import ar.com.longcasting.feature.rosters.RostersUiState
import ar.com.longcasting.feature.rosters.rosterKeys
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose exige que las claves de un LazyColumn sean unicas en toda la lista, no dentro de
 * cada bloque `items`. Cuando se pisan, la app no falla al compilar: aborta en tiempo de
 * ejecucion durante el layout con "Key N was already used", que es como crasheaba la pestana
 * Gente en cuanto habia un tirador con el mismo id que una plomada.
 *
 * Las pantallas que combinan dos listas en un solo LazyColumn exponen la lista completa de
 * claves justamente para poder verificar esa propiedad aca.
 */
class LazyListKeysTest {

    private fun shooter(id: Long) = Shooter(id = id, name = "Tirador $id")

    private fun sinker(id: Long) = Sinker(id = id, grams = id * 25, label = "${id * 25} g")

    private fun best(shooterId: Long, sinkerId: Long) = RankingEntry(
        shooterId = shooterId,
        shooterName = "Tirador $shooterId",
        sinkerId = sinkerId,
        sinkerLabel = "plomada $sinkerId",
        sinkerGrams = 100,
        bestCm = 20000,
        averageCm = 19000,
        throwCount = 3,
        lastAt = 0,
    )

    private fun throwRow(id: Long) = ThrowSummary(
        id = id,
        sessionId = 1,
        createdAt = 0,
        officialCm = 20000,
        gpsDistanceM = 200.0,
        gpsSigmaM = 1.0,
        pathLengthM = null,
        tapeDistanceCm = null,
        bearingDeg = null,
        valid = true,
        invalidReason = null,
        notes = null,
        shooterId = 1,
        shooterName = "Tirador 1",
        sinkerId = 1,
        sinkerLabel = "100 g",
        sinkerGrams = 100,
    )

    private fun assertNoDuplicates(keys: List<String>) {
        val duplicates = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet(), duplicates, "claves repetidas en $keys")
    }

    @Test
    fun `en Gente los ids que coinciden no producen la misma clave`() {
        // Es el caso que crasheaba: la carga inicial da plomadas con id 1..4 y el primer
        // tirador que se agrega tambien queda con id 1.
        val state = RostersUiState(
            shooters = listOf(shooter(1), shooter(2)),
            sinkers = listOf(sinker(1), sinker(2), sinker(3), sinker(4)),
        )
        assertEquals(6, rosterKeys(state).size)
        assertNoDuplicates(rosterKeys(state))
    }

    @Test
    fun `en Gente cada lista por separado tampoco repite`() {
        assertNoDuplicates(rosterKeys(RostersUiState(shooters = (1L..20L).map(::shooter))))
        assertNoDuplicates(rosterKeys(RostersUiState(sinkers = (1L..20L).map(::sinker))))
    }

    @Test
    fun `en Historial las mejores marcas no chocan con los tiros`() {
        val state = HistoryUiState(
            ranking = listOf(best(1, 1), best(1, 2), best(2, 1)),
            throws = listOf(throwRow(1), throwRow(2), throwRow(3)),
        )
        assertEquals(6, historyKeys(state).size)
        assertNoDuplicates(historyKeys(state))
    }

    @Test
    fun `en Historial una marca por combinacion de tirador y plomada`() {
        // "1-2" y "12-" no pueden colapsar en la misma clave.
        val state = HistoryUiState(ranking = listOf(best(1, 12), best(11, 2), best(112, 0)))
        assertNoDuplicates(historyKeys(state))
    }

    @Test
    fun `las listas vacias no rompen nada`() {
        assertNoDuplicates(rosterKeys(RostersUiState()))
        assertNoDuplicates(historyKeys(HistoryUiState()))
    }
}
