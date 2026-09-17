package ar.com.longcasting.core.measure

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.gnss.Fix
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Distancia total recorrida.
 *
 * Sumar el desplazamiento entre cada par de fixes consecutivos no funciona, por dos razones
 * distintas que hay que atacar por separado:
 *
 * 1. **Deriva estando quieto.** Con 3 m de precision, el desplazamiento entre dos fixes
 *    consecutivos supera los 4,5 m mas de la mitad de las veces por puro ruido. Cualquier
 *    umbral chico deja pasar cientos de metros fantasma. Lo que si distingue de verdad estar
 *    parado de caminar es la **velocidad**, que el receptor calcula por Doppler y es mucho
 *    mas precisa que la posicion (tipicamente 0,05-0,2 m/s). Por eso el acumulador ignora
 *    los fixes cuya velocidad no llega a [movingSpeedMps].
 *
 * 2. **Sobreestimacion por trocear.** La norma de un desplazamiento con ruido siempre es
 *    mayor que la del desplazamiento verdadero, asi que cortar el recorrido en tramos cortos
 *    y sumar normas infla el total. Se corrige con un ancla generosa (tramos largos, donde
 *    el desplazamiento real domina al ruido) y quitandole al cuadrado la varianza esperada:
 *    `E[L2] = D2 + 2*sigma2`, entonces `D = sqrt(L2 - 2*sigma2)`.
 *
 * Es un numero secundario: el que importa para el tiro es la distancia en linea recta. Un
 * error de algunos por ciento en el recorrido total es aceptable.
 */
class PathLengthAccumulator(
    private val frame: EnuFrame,
    /** Tramo minimo antes de acumular. Generoso a proposito: ver punto 2 del comentario. */
    private val minSegmentM: Double = 10.0,
    private val accuracyFactor: Double = 4.0,
    private val maxAccuracyM: Double = 25.0,
    /** Por debajo de esto se considera que el equipo esta quieto y el fix se descarta. */
    private val movingSpeedMps: Double = 0.4,
) {
    private var anchor: Vec3? = null
    private var anchorAccuracyM: Double = 0.0

    var totalM: Double = 0.0
        private set

    fun add(fix: Fix): Double {
        if (fix.accuracyM > maxAccuracyM) return totalM

        // Sin velocidad no se puede distinguir estar quieto de caminar y solo queda la
        // compuerta geometrica. En Android el proveedor GPS casi siempre la entrega.
        val speed = fix.speedMps
        if (speed != null && speed < movingSpeedMps) return totalM

        val point = frame.toEnu(fix.geo)
        val current = anchor
        if (current == null) {
            anchor = point
            anchorAccuracyM = fix.accuracyM
            return totalM
        }

        val measured = (point - current).horizontalNorm
        val gate = max(minSegmentM, accuracyFactor * fix.accuracyM)
        if (measured >= gate) {
            totalM += debias(measured, anchorAccuracyM, fix.accuracyM)
            anchor = point
            anchorAccuracyM = fix.accuracyM
        }
        return totalM
    }

    /**
     * Cierra el recorrido contra el punto de caida ya promediado, sumando el tramo que quedo
     * pendiente por no llegar a la compuerta. Como ese punto viene de una ocupacion estatica,
     * su incertidumbre es mucho menor que la de un fix suelto y el tramo final vale la pena.
     */
    fun finish(point: Geo, accuracyM: Double): Double {
        val current = anchor ?: return totalM
        val measured = (frame.toEnu(point) - current).horizontalNorm
        totalM += debias(measured, anchorAccuracyM, accuracyM)
        anchor = null
        return totalM
    }

    fun reset() {
        anchor = null
        anchorAccuracyM = 0.0
        totalM = 0.0
    }

    /**
     * Quita del tramo medido la parte que aporta el ruido. `accuracyM` se toma como el radio
     * de error a 1 sigma, asi que la varianza por eje de la diferencia entre dos fixes es
     * `(a1^2 + a2^2) / 2`, y sobre los dos ejes horizontales suma `a1^2 + a2^2`.
     */
    private fun debias(measured: Double, accuracyA: Double, accuracyB: Double): Double {
        val noiseVariance = accuracyA * accuracyA + accuracyB * accuracyB
        val corrected = measured * measured - noiseVariance
        return if (corrected > 0.0) sqrt(corrected) else 0.0
    }
}
