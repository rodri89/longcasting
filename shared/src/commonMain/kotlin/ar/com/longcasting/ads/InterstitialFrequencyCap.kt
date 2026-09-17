package ar.com.longcasting.ads

import ar.com.longcasting.db.LongcastingDatabase
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Tope de un intersticial por dia calendario (hora local del telefono). Reutiliza la tabla
 * `app_setting` que ya usa `SettingsRepository`: son cuatro strings, no amerita una tabla propia.
 *
 * A proposito **no** son `suspend`: es una lectura/escritura de una sola fila (rapida, sin red),
 * y del lado de iOS `SwiftAdsService` las llama directo, sin la friccion extra de bridgear
 * `suspend` de Kotlin/Native a Swift para algo tan chico.
 */
class InterstitialFrequencyCap(private val database: LongcastingDatabase) {

    /** true si todavia no se mostro ningun intersticial hoy. No marca nada por si sola. */
    fun canShowToday(): Boolean =
        database.appSettingQueries.get(KEY).executeAsOneOrNull() != todayKey()

    /** Registra que se mostro un intersticial hoy, para que `canShowToday()` corte el resto del dia. */
    fun markShownToday() {
        database.appSettingQueries.put(KEY, todayKey())
    }

    @OptIn(ExperimentalTime::class)
    private fun todayKey(): String {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return today.toString() // ISO-8601, p.ej. "2026-09-05"
    }

    private companion object {
        const val KEY = "ads_last_interstitial_date"
    }
}
