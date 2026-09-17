package ar.com.longcasting.core.model

data class Shooter(
    val id: Long,
    val name: String,
    val notes: String? = null,
)

data class Sinker(
    val id: Long,
    val grams: Long,
    val label: String,
    /** Diametro minimo de hilo que exige el reglamento para este peso, en milimetros. */
    val minLineMm: Double? = null,
)

data class CastingSession(
    val id: Long,
    val name: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val windNote: String?,
    val notes: String?,
)

/** Un tiro con los nombres ya resueltos, tal como lo muestra el historial. */
data class ThrowSummary(
    val id: Long,
    val sessionId: Long,
    val createdAt: Long,
    val officialCm: Int,
    val gpsDistanceM: Double?,
    val gpsSigmaM: Double?,
    val pathLengthM: Double?,
    val tapeDistanceCm: Int?,
    val bearingDeg: Double?,
    val valid: Boolean,
    val invalidReason: String?,
    val notes: String?,
    val shooterId: Long,
    val shooterName: String,
    val sinkerId: Long,
    val sinkerLabel: String,
    val sinkerGrams: Long,
    /** Tomado con un solo fix, sin promediar: trae varios metros de error. */
    val quickMode: Boolean = false,
) {
    /** La medida de cinta manda sobre la del GPS cuando existe. */
    val measuredWithTape: Boolean get() = tapeDistanceCm != null
}

/** Una fila del ranking: la mejor marca de un tirador con una plomada dada. */
data class RankingEntry(
    val shooterId: Long,
    val shooterName: String,
    val sinkerId: Long,
    val sinkerLabel: String,
    val sinkerGrams: Long,
    val bestCm: Int,
    val averageCm: Int,
    val throwCount: Int,
    val lastAt: Long,
    /** La mejor marca del grupo se tomo en modo rapido: hay que mostrarlo, no esconderlo. */
    val bestWasQuick: Boolean = false,
)

/** Pesos de plomada del reglamento, con su diametro minimo de hilo. */
val STANDARD_SINKERS = listOf(
    Triple(100L, "100 g", 0.25),
    Triple(125L, "125 g", 0.28),
    Triple(150L, "150 g", 0.31),
    Triple(175L, "175 g", 0.35),
)
