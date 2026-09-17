package ar.com.longcasting.core.measure

/**
 * Cuando cortar una ocupacion estatica.
 *
 * Antes se esperaba siempre el tiempo completo, aunque la solucion ya hubiera convergido. Con
 * cielo abierto la incertidumbre suele estabilizarse bastante antes del minuto, y esos
 * segundos de mas no aportan nada: el tirador esta parado al pedo.
 *
 * Aca no hay atajo de precision. La sigma que se compara ya se calcula con tamano de muestra
 * efectivo (ver [StaticPointEstimator]), asi que **no puede** bajar del objetivo solo por
 * haber juntado muchas muestras seguidas: hace falta que la solucion este realmente quieta.
 * Si las condiciones son malas, nunca alcanza el objetivo y se agota el tiempo maximo, que es
 * exactamente lo que corresponde.
 */
data class OccupationTarget(
    /** Piso de tiempo: por debajo de esto la sigma todavia no es representativa. */
    val minSeconds: Int = 15,
    val maxSeconds: Int = 60,
    /** Incertidumbre buscada por punto. El tiro combina dos puntos, asi que da ~1,4 veces esto. */
    val targetSigmaM: Double = 1.5,
    /** Muestras minimas antes de creerle a la sigma. */
    val minSamples: Int = 10,
)

enum class OccupationStop {
    /** Todavia no: seguir promediando. */
    CONTINUE,

    /** Se alcanzo la precision buscada antes del tiempo maximo. */
    TARGET_REACHED,

    /** Se agoto el tiempo sin llegar al objetivo. Vale igual, pero con mas incertidumbre. */
    TIMEOUT,
}

fun decideOccupation(
    elapsedSec: Double,
    sigmaM: Double?,
    acceptedSamples: Int,
    target: OccupationTarget,
): OccupationStop = when {
    elapsedSec >= target.maxSeconds -> OccupationStop.TIMEOUT
    elapsedSec < target.minSeconds -> OccupationStop.CONTINUE
    acceptedSamples < target.minSamples -> OccupationStop.CONTINUE
    sigmaM != null && sigmaM <= target.targetSigmaM -> OccupationStop.TARGET_REACHED
    else -> OccupationStop.CONTINUE
}
