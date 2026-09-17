package ar.com.longcasting

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.measure.OccupationStop
import ar.com.longcasting.core.measure.OccupationTarget
import ar.com.longcasting.core.measure.StaticPointEstimator
import ar.com.longcasting.core.measure.decideOccupation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OccupationDecisionTest {

    private val target = OccupationTarget(
        minSeconds = 15,
        maxSeconds = 60,
        targetSigmaM = 1.5,
        minSamples = 10,
    )

    private fun decide(elapsed: Double, sigma: Double?, samples: Int = 40) =
        decideOccupation(elapsed, sigma, samples, target)

    @Test
    fun `corta apenas alcanza la precision buscada`() {
        assertEquals(OccupationStop.TARGET_REACHED, decide(elapsed = 22.0, sigma = 1.4))
        assertEquals(OccupationStop.TARGET_REACHED, decide(elapsed = 15.0, sigma = 1.5))
    }

    @Test
    fun `sigue mientras la precision no alcance`() {
        assertEquals(OccupationStop.CONTINUE, decide(elapsed = 30.0, sigma = 2.4))
        assertEquals(OccupationStop.CONTINUE, decide(elapsed = 59.0, sigma = 1.51))
    }

    @Test
    fun `no corta antes del piso de tiempo aunque la sigma diga que si`() {
        // Con pocos segundos la sigma todavia no es representativa: no se le cree.
        assertEquals(OccupationStop.CONTINUE, decide(elapsed = 5.0, sigma = 0.2))
        assertEquals(OccupationStop.CONTINUE, decide(elapsed = 14.9, sigma = 0.2))
    }

    @Test
    fun `no corta con pocas muestras`() {
        assertEquals(OccupationStop.CONTINUE, decide(elapsed = 40.0, sigma = 0.5, samples = 9))
        assertEquals(OccupationStop.TARGET_REACHED, decide(elapsed = 40.0, sigma = 0.5, samples = 10))
    }

    @Test
    fun `sin sigma no corta por precision`() {
        assertEquals(OccupationStop.CONTINUE, decide(elapsed = 40.0, sigma = null))
    }

    @Test
    fun `el tiempo maximo manda por encima de todo`() {
        assertEquals(OccupationStop.TIMEOUT, decide(elapsed = 60.0, sigma = 8.0))
        assertEquals(OccupationStop.TIMEOUT, decide(elapsed = 90.0, sigma = null, samples = 0))
    }

    /**
     * La propiedad que sostiene todo el atajo: la sigma se calcula con tamano de muestra
     * efectivo, asi que juntar muchas muestras seguidas **no** alcanza para bajarla. Si esto
     * dejara de valer, cortar temprano seria regalar precision sin darse cuenta.
     */
    @Test
    fun `no se puede llegar al objetivo solo por acumular muestras`() {
        val frame = EnuFrame(TEST_ORIGIN)
        val noise = Gaussian(41)
        // Ruido de 6 m: una senal mala. Ni con 60 s de muestras deberia dar por buena la toma.
        val fixes = (0 until 60).map { i ->
            noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L + i * 1000L, 6.0, noise)
        }
        val point = StaticPointEstimator.estimate(fixes)!!
        assertTrue(
            point.sigmaM > target.targetSigmaM,
            "con 6 m de ruido la sigma dio ${point.sigmaM}: el corte temprano seria mentira",
        )
        assertEquals(
            OccupationStop.CONTINUE,
            decide(elapsed = 59.0, sigma = point.sigmaM, samples = point.samplesAccepted),
        )
    }

    @Test
    fun `con senal buena si corta antes de tiempo`() {
        val frame = EnuFrame(TEST_ORIGIN)
        val noise = Gaussian(43)
        // Ruido de 1 m: cielo abierto y equipo quieto. A los 25 s ya deberia estar.
        val fixes = (0 until 25).map { i ->
            noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L + i * 1000L, 1.0, noise)
        }
        val point = StaticPointEstimator.estimate(fixes)!!
        assertEquals(
            OccupationStop.TARGET_REACHED,
            decide(elapsed = 24.0, sigma = point.sigmaM, samples = point.samplesAccepted),
            "con 1 m de ruido la sigma dio ${point.sigmaM} y deberia alcanzar el objetivo",
        )
    }
}
