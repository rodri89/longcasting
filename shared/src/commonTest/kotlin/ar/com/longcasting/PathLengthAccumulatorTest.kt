package ar.com.longcasting

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.measure.LiveTracker
import ar.com.longcasting.core.measure.PathLengthAccumulator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathLengthAccumulatorTest {

    private val frame = EnuFrame(TEST_ORIGIN)

    @Test
    fun `quieto tres minutos no acumula nada`() {
        val noise = Gaussian(13)
        val accumulator = PathLengthAccumulator(frame)
        repeat(300) { i ->
            accumulator.add(
                noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L + i * 1000L, 3.0, noise, speedMps = 0.0)
            )
        }
        assertEquals(0.0, accumulator.totalM, "el ruido estando parado no puede sumar metros")
    }

    @Test
    fun `una caminata de doscientos metros mide doscientos metros`() {
        val noise = Gaussian(17)
        val accumulator = PathLengthAccumulator(frame)
        val speed = 1.4
        val steps = (200.0 / speed).toInt()
        for (i in 0..steps) {
            val advanced = (i * speed).coerceAtMost(200.0)
            accumulator.add(
                noisyFix(
                    frame, Vec3(advanced, 0.0, 0.0), 1_700_000_000_000L + i * 1000L,
                    3.0, noise, speedMps = speed,
                )
            )
        }
        // El tramo final se cierra contra el punto de caida ya promediado.
        val total = accumulator.finish(frame.toGeo(Vec3(200.0, 0.0, 0.0)), 0.5)
        assertTrue(abs(total - 200.0) < 10.0, "el recorrido dio $total m en vez de 200")
    }

    @Test
    fun `caminar y frenar a mitad de camino no infla el total`() {
        val noise = Gaussian(19)
        val accumulator = PathLengthAccumulator(frame)
        val speed = 1.4
        var t = 1_700_000_000_000L
        var advanced = 0.0

        while (advanced < 100.0) {
            accumulator.add(
                noisyFix(frame, Vec3(advanced, 0.0, 0.0), t, 3.0, noise, speedMps = speed)
            )
            advanced += speed
            t += 1000
        }
        // Dos minutos parado a mitad de camino, buscando la plomada.
        repeat(120) {
            accumulator.add(noisyFix(frame, Vec3(100.0, 0.0, 0.0), t, 3.0, noise, speedMps = 0.0))
            t += 1000
        }
        while (advanced < 200.0) {
            accumulator.add(
                noisyFix(frame, Vec3(advanced, 0.0, 0.0), t, 3.0, noise, speedMps = speed)
            )
            advanced += speed
            t += 1000
        }
        val total = accumulator.finish(frame.toGeo(Vec3(200.0, 0.0, 0.0)), 0.5)
        assertTrue(abs(total - 200.0) < 12.0, "la parada inflo el recorrido a $total m")
    }

    @Test
    fun `ignora los fixes de precision inaceptable`() {
        val noise = Gaussian(23)
        val accumulator = PathLengthAccumulator(frame, maxAccuracyM = 25.0)
        accumulator.add(noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L, 3.0, noise, speedMps = 1.4))
        accumulator.add(
            noisyFix(frame, Vec3(500.0, 0.0, 0.0), 1_700_000_001_000L, 90.0, noise, speedMps = 1.4)
        )
        assertEquals(0.0, accumulator.totalM, "un fix de 90 m de error no puede sumar 500 m")
    }

    @Test
    fun `el tracker en vivo mide la recta contra el origen`() {
        val noise = Gaussian(29)
        val tracker = LiveTracker(TEST_ORIGIN)
        val speed = 1.4
        val steps = (220.0 / speed).toInt()
        var state = tracker.state
        for (i in 0..steps) {
            val advanced = (i * speed).coerceAtMost(220.0)
            state = tracker.onFix(
                noisyFix(
                    frame, Vec3(advanced * 0.6, advanced * 0.8, 0.0),
                    1_700_000_000_000L + i * 1000L, 3.0, noise, speedMps = speed,
                )
            )
        }
        assertTrue(state.hasFix)
        assertTrue(
            abs(state.smoothedStraightLineM - 220.0) < 6.0,
            "la recta dio ${state.smoothedStraightLineM} m en vez de 220",
        )
        // El rumbo del vector (0,6 este, 0,8 norte) es atan2(0,6 / 0,8) = 36,87 grados.
        val bearing = state.bearingDeg
        assertTrue(bearing != null && abs(bearing - 36.87) < 5.0, "rumbo $bearing")
    }

    @Test
    fun `el tracker no informa rumbo pegado al origen`() {
        val noise = Gaussian(31)
        val tracker = LiveTracker(TEST_ORIGIN)
        val state = tracker.onFix(
            noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L, 0.5, noise, speedMps = 0.0)
        )
        assertTrue(state.bearingDeg == null, "pegado al vertice el rumbo es solo ruido")
    }
}
