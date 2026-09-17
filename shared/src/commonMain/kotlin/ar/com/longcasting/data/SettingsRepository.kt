package ar.com.longcasting.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ar.com.longcasting.db.LongcastingDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Ajustes que el usuario toca en cancha y tienen que sobrevivir al cierre de la app. */
data class AppSettings(
    /**
     * Tope de segundos de ocupacion estatica en cada extremo del tiro. Es un maximo, no un
     * tiempo fijo: si la incertidumbre baja de [targetSigmaM] antes, la toma se cierra sola.
     */
    val occupationSeconds: Int = 60,
    /** Incertidumbre buscada por punto. Al alcanzarla, la ocupacion termina sin esperar mas. */
    val targetSigmaM: Double = 1.5,
    /** Precision peor que esto descarta la muestra. */
    val maxAccuracyM: Double = 12.0,
    val minSats: Int = 5,
    /** Aviso sonoro y vibracion al terminar una ocupacion. */
    val hapticFeedback: Boolean = true,
    /**
     * Modo rapido: un solo fix por punto, sin promediar. Instantaneo y con varios metros de
     * error. Se persiste, asi que la pantalla de medir tiene que dejarlo bien a la vista.
     */
    val quickMode: Boolean = false,
) {
    companion object {
        const val MIN_OCCUPATION_SECONDS = 15
        const val MAX_OCCUPATION_SECONDS = 300

        /** Por debajo de esto la sigma todavia no es representativa. */
        const val FLOOR_OCCUPATION_SECONDS = 15
    }
}

class SettingsRepository(
    private val database: LongcastingDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observe(): Flow<AppSettings> =
        database.appSettingQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            val map = rows.associate { it.key to it.value_ }
            AppSettings(
                occupationSeconds = map[KEY_OCCUPATION]?.toIntOrNull()
                    ?: AppSettings().occupationSeconds,
                maxAccuracyM = map[KEY_MAX_ACCURACY]?.toDoubleOrNull()
                    ?: AppSettings().maxAccuracyM,
                targetSigmaM = map[KEY_TARGET_SIGMA]?.toDoubleOrNull()
                    ?: AppSettings().targetSigmaM,
                minSats = map[KEY_MIN_SATS]?.toIntOrNull() ?: AppSettings().minSats,
                hapticFeedback = map[KEY_HAPTIC]?.toBooleanStrictOrNull()
                    ?: AppSettings().hapticFeedback,
                quickMode = map[KEY_QUICK]?.toBooleanStrictOrNull() ?: AppSettings().quickMode,
            )
        }

    suspend fun setOccupationSeconds(seconds: Int) = put(
        KEY_OCCUPATION,
        seconds.coerceIn(
            AppSettings.MIN_OCCUPATION_SECONDS,
            AppSettings.MAX_OCCUPATION_SECONDS,
        ).toString(),
    )

    suspend fun setTargetSigma(meters: Double) =
        put(KEY_TARGET_SIGMA, meters.coerceIn(0.3, 5.0).toString())

    suspend fun setMaxAccuracy(meters: Double) =
        put(KEY_MAX_ACCURACY, meters.coerceIn(3.0, 30.0).toString())

    suspend fun setMinSats(count: Int) = put(KEY_MIN_SATS, count.coerceIn(4, 12).toString())

    suspend fun setHapticFeedback(enabled: Boolean) = put(KEY_HAPTIC, enabled.toString())

    suspend fun setQuickMode(enabled: Boolean) = put(KEY_QUICK, enabled.toString())

    private suspend fun put(key: String, value: String) = withContext(dispatcher) {
        database.appSettingQueries.put(key, value)
    }

    private companion object {
        const val KEY_OCCUPATION = "occupation_seconds"
        const val KEY_MAX_ACCURACY = "max_accuracy_m"
        const val KEY_TARGET_SIGMA = "target_sigma_m"
        const val KEY_MIN_SATS = "min_sats"
        const val KEY_HAPTIC = "haptic_feedback"
        const val KEY_QUICK = "quick_mode"
    }
}
