package ar.com.longcasting.core.measure

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.gnss.Fix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class OccupationConfig(
    /** Al frenar, la solucion todavia esta convergiendo: los primeros segundos se descartan. */
    val warmupSec: Double = 5.0,
    val maxAccuracyM: Double = 12.0,
    val minSats: Int = 5,
    val madCutoff: Double = 3.0,
    val madIterations: Int = 2,
    /**
     * Tiempo de decorrelacion tipico del ruido GNSS, en segundos. Define el tamano de muestra
     * efectivo: `n_eff = duracion / correlationTimeSec`. Sin esto, promediar 120 epochs
     * consecutivos parece reducir el error por raiz de 120 y la app reportaria una precision
     * de centimetros que no existe.
     */
    val correlationTimeSec: Double = 30.0,
    /**
     * Piso de incertidumbre. El multipath sobre la antena de un telefono es un sesgo
     * correlacionado que promediar no elimina, asi que por debajo de esto el numero
     * dejaria de ser honesto por mas larga que sea la ocupacion.
     */
    val sigmaFloorM: Double = 0.15,
    /** Algunos equipos declaran precisiones absurdamente optimistas; se acotan al ponderar. */
    val minReportedAccuracyM: Double = 0.5,
)

/**
 * Punto promediado de una ocupacion estatica.
 *
 * [sigmaM] es una estimacion de *repetibilidad* (que tanto se movio la solucion durante la
 * toma), no de exactitud absoluta. El numero real de exactitud sale de la prueba de cierre
 * y de la prueba con cinta en cancha.
 */
data class StaticPoint(
    val geo: Geo,
    val sigmaM: Double,
    val samplesAccepted: Int,
    val samplesRejected: Int,
    val durationSec: Double,
    val spreadRmsM: Double,
    val meanCn0DbHz: Double?,
    val satsUsed: Int?,
    val hasL5: Boolean,
)

object StaticPointEstimator {

    /** Devuelve null si no quedan muestras con calidad suficiente para dar un punto. */
    fun estimate(fixes: List<Fix>, config: OccupationConfig = OccupationConfig()): StaticPoint? {
        if (fixes.isEmpty()) return null
        val sorted = fixes.sortedBy { it.tMillis }
        val t0 = sorted.first().tMillis

        // Si la ocupacion fue mas corta que el warmup, es preferible usar todo a no dar nada.
        val afterWarmup = sorted.filter { (it.tMillis - t0) / 1000.0 >= config.warmupSec }
        val pool = afterWarmup.ifEmpty { sorted }

        val candidates = pool.filter { fix ->
            fix.accuracyM <= config.maxAccuracyM &&
                (fix.satsUsed == null || fix.satsUsed >= config.minSats)
        }
        if (candidates.isEmpty()) return null

        val frame = EnuFrame(candidates.first().geo)
        val pts = candidates.map { frame.toEnu(it.geo) }

        var kept = candidates.indices.toList()
        repeat(config.madIterations) {
            if (kept.size >= 4) kept = rejectOutliers(kept, pts, config.madCutoff)
        }

        val weight = { i: Int ->
            val a = max(config.minReportedAccuracyM, candidates[i].accuracyM)
            1.0 / (a * a)
        }

        var sumW = 0.0
        var sumE = 0.0
        var sumN = 0.0
        var sumU = 0.0
        for (i in kept) {
            val w = weight(i)
            sumW += w
            sumE += w * pts[i].x
            sumN += w * pts[i].y
            sumU += w * pts[i].z
        }
        val centre = Vec3(sumE / sumW, sumN / sumW, sumU / sumW)

        var sumSq = 0.0
        for (i in kept) {
            val w = weight(i)
            val dx = pts[i].x - centre.x
            val dy = pts[i].y - centre.y
            sumSq += w * (dx * dx + dy * dy)
        }
        val spreadRms = sqrt(sumSq / sumW)

        val times = kept.map { candidates[it].tMillis }
        val durationSec = ((times.max() - times.min()) / 1000.0).coerceAtLeast(0.0)
        val nEff = max(1.0, durationSec / config.correlationTimeSec)
        val sigma = max(config.sigmaFloorM, spreadRms / sqrt(nEff))

        val cn0 = kept.mapNotNull { candidates[it].meanCn0DbHz }
        val sats = kept.mapNotNull { candidates[it].satsUsed }

        return StaticPoint(
            geo = frame.toGeo(centre),
            sigmaM = sigma,
            samplesAccepted = kept.size,
            samplesRejected = pool.size - kept.size,
            durationSec = durationSec,
            spreadRmsM = spreadRms,
            // iOS no informa ninguno de los dos: hay que resolverlo antes de promediar,
            // porque `average()` sobre una lista vacia da NaN.
            meanCn0DbHz = if (cn0.isEmpty()) null else cn0.average(),
            satsUsed = if (sats.isEmpty()) null else sats.average().roundToInt(),
            hasL5 = kept.any { candidates[it].hasL5 },
        )
    }

    /**
     * Rechazo robusto por MAD sobre el radio horizontal respecto de la mediana. Se usa mediana
     * y no media justamente para que los outliers no corran el centro antes de descartarlos.
     */
    private fun rejectOutliers(indices: List<Int>, pts: List<Vec3>, cutoff: Double): List<Int> {
        val centreE = median(indices.map { pts[it].x })
        val centreN = median(indices.map { pts[it].y })
        val radii = indices.map { i ->
            val dx = pts[i].x - centreE
            val dy = pts[i].y - centreN
            sqrt(dx * dx + dy * dy)
        }
        val medianR = median(radii)
        val mad = median(radii.map { abs(it - medianR) })
        val sigma = 1.4826 * mad
        if (sigma <= 1e-6) return indices
        val limit = medianR + cutoff * sigma
        val kept = indices.filterIndexed { k, _ -> radii[k] <= limit }
        return if (kept.size >= 4) kept else indices
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}

/**
 * Incertidumbre de la distancia entre dos puntos ocupados. Es conservador: ignora que buena
 * parte del error es comun a ambos extremos y por lo tanto se cancela en la resta.
 */
fun combinedSigma(origin: StaticPoint, landing: StaticPoint): Double =
    sqrt(origin.sigmaM * origin.sigmaM + landing.sigmaM * landing.sigmaM)
