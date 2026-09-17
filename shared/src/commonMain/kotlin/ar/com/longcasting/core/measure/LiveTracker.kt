package ar.com.longcasting.core.measure

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.geo.bearingDegrees
import ar.com.longcasting.core.gnss.Fix

data class LiveState(
    val hasFix: Boolean = false,
    /** Distancia recta al origen segun el ultimo fix, sin suavizar. */
    val straightLineM: Double = 0.0,
    /** La misma distancia suavizada, que es la que conviene mostrar para que no tiemble. */
    val smoothedStraightLineM: Double = 0.0,
    val pathLengthM: Double = 0.0,
    val accuracyM: Double? = null,
    val bearingDeg: Double? = null,
    val satsUsed: Int? = null,
    val fixCount: Int = 0,
)

/**
 * Estado en vivo mientras el tirador camina del vertice hacia la plomada.
 *
 * La distancia recta se calcula contra el punto de origen ya promediado, no contra el primer
 * fix crudo: el origen viene de una ocupacion estatica y es mucho mejor que cualquier
 * posicion instantanea.
 */
class LiveTracker(
    origin: Geo,
    smoothingWindow: Int = 5,
) {
    private val frame = EnuFrame(origin)
    private val path = PathLengthAccumulator(frame)
    private val alpha = 2.0 / (smoothingWindow + 1)
    private var ema: Double? = null

    var state: LiveState = LiveState()
        private set

    fun onFix(fix: Fix): LiveState {
        val enu = frame.toEnu(fix.geo)
        val straight = enu.horizontalNorm
        val smoothed = ema?.let { it + alpha * (straight - it) } ?: straight
        ema = smoothed

        state = LiveState(
            hasFix = true,
            straightLineM = straight,
            smoothedStraightLineM = smoothed,
            pathLengthM = path.add(fix),
            accuracyM = fix.accuracyM,
            // Cerca del origen el rumbo es solo ruido, no tiene sentido mostrarlo.
            bearingDeg = if (straight > 5.0) bearingDegrees(enu) else null,
            satsUsed = fix.satsUsed,
            fixCount = state.fixCount + 1,
        )
        return state
    }

    /** Cierra el recorrido contra el punto de caida ya promediado. Ver [PathLengthAccumulator.finish]. */
    fun finishPath(landing: Geo, accuracyM: Double): Double = path.finish(landing, accuracyM)

    fun reset() {
        path.reset()
        ema = null
        state = LiveState()
    }
}
