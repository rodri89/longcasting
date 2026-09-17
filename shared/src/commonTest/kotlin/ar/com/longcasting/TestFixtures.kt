package ar.com.longcasting

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.gnss.Fix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Un punto cualquiera a cielo abierto; sirve de vertice para todas las pruebas. */
val TEST_ORIGIN = Geo(lat = -34.6037, lon = -58.3816, altM = 25.0)

/** Ruido gaussiano reproducible (Box-Muller sobre una semilla fija). */
class Gaussian(seed: Int) {
    private val random = Random(seed)

    fun next(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    fun nextAngle(): Double = random.nextDouble() * 2.0 * PI
}

/**
 * `accuracyM` se interpreta como el radio de error a 1 sigma, asi que el ruido por eje es
 * `accuracyM / sqrt(2)`: es la misma convencion que usan el estimador y el acumulador.
 */
fun perAxisSigma(accuracyM: Double): Double = accuracyM / sqrt(2.0)

/** Construye un fix ruidoso alrededor de una posicion verdadera dada en metros ENU. */
fun noisyFix(
    frame: EnuFrame,
    truth: Vec3,
    tMillis: Long,
    accuracyM: Double,
    noise: Gaussian,
    speedMps: Double? = null,
    satsUsed: Int? = 9,
    meanCn0DbHz: Double? = 38.0,
    hasL5: Boolean = false,
): Fix {
    val sigma = perAxisSigma(accuracyM)
    val geo = frame.toGeo(
        Vec3(
            x = truth.x + noise.next() * sigma,
            y = truth.y + noise.next() * sigma,
            z = truth.z + noise.next() * sigma,
        )
    )
    return Fix(
        tMillis = tMillis,
        lat = geo.lat,
        lon = geo.lon,
        altM = geo.altM,
        accuracyM = accuracyM,
        speedMps = speedMps,
        satsUsed = satsUsed,
        satsVisible = satsUsed?.plus(4),
        meanCn0DbHz = meanCn0DbHz,
        hasL5 = hasL5,
    )
}
