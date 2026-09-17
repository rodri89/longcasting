package ar.com.longcasting

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.geo.horizontalDistance
import ar.com.longcasting.core.gnss.Fix
import ar.com.longcasting.core.measure.OccupationConfig
import ar.com.longcasting.core.measure.StaticPointEstimator
import ar.com.longcasting.core.measure.combinedSigma
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaticPointEstimatorTest {

    private val frame = EnuFrame(TEST_ORIGIN)

    /** 120 epochs a 1 Hz sobre un punto quieto, mas outliers a 40 m repartidos en el medio. */
    private fun occupation(
        accuracyM: Double = 5.0,
        samples: Int = 120,
        outliers: Int = 10,
        seed: Int = 7,
        truth: Vec3 = Vec3.ZERO,
    ): List<Fix> {
        val noise = Gaussian(seed)
        val fixes = ArrayList<Fix>(samples)
        val outlierEvery = if (outliers > 0) samples / outliers else Int.MAX_VALUE
        for (i in 0 until samples) {
            val t = 1_700_000_000_000L + i * 1000L
            val isOutlier = i > 0 && i % outlierEvery == 0
            val centre = if (isOutlier) {
                val angle = noise.nextAngle()
                Vec3(truth.x + 40.0 * cos(angle), truth.y + 40.0 * sin(angle), truth.z)
            } else {
                truth
            }
            fixes += noisyFix(frame, centre, t, accuracyM, noise)
        }
        return fixes
    }

    @Test
    fun `recupera el centro pese al ruido y a los outliers`() {
        val point = StaticPointEstimator.estimate(occupation())
        assertNotNull(point)
        val error = horizontalDistance(frame.toGeo(Vec3.ZERO), point.geo)
        assertTrue(error < 1.5, "el centro quedo a $error m del verdadero")
    }

    @Test
    fun `descarta los outliers`() {
        val point = StaticPointEstimator.estimate(occupation(outliers = 10))
        assertNotNull(point)
        // Los 10 saltos a 40 m estan a ocho sigmas del centro: no puede quedar ninguno.
        assertTrue(point.samplesRejected >= 8, "solo rechazo ${point.samplesRejected}")
        assertTrue(point.samplesAccepted > 90, "acepto solo ${point.samplesAccepted}")
    }

    @Test
    fun `la incertidumbre se calcula con el tamano de muestra efectivo`() {
        val config = OccupationConfig()
        val point = StaticPointEstimator.estimate(occupation(), config)
        assertNotNull(point)

        val nEff = point.durationSec / config.correlationTimeSec
        val expected = point.spreadRmsM / sqrt(nEff)
        assertTrue(
            abs(point.sigmaM - expected) < 1e-6,
            "sigma ${point.sigmaM} no coincide con spread/raiz(n_eff) = $expected",
        )

        // La prueba que de verdad importa: con ~120 muestras, dividir por raiz de 120 en vez
        // de por raiz de n_eff daria una decima de metro. Ese numero seria mentira.
        assertTrue(
            point.sigmaM > 0.5,
            "sigma ${point.sigmaM} es demasiado optimista: se esta ignorando la autocorrelacion",
        )
        assertTrue(point.sigmaM < 6.0, "sigma ${point.sigmaM} es implausiblemente alta")
    }

    @Test
    fun `una ocupacion mas larga baja la incertidumbre`() {
        val corta = StaticPointEstimator.estimate(occupation(samples = 30, outliers = 0))
        val larga = StaticPointEstimator.estimate(occupation(samples = 240, outliers = 0))
        assertNotNull(corta)
        assertNotNull(larga)
        assertTrue(
            larga.sigmaM < corta.sigmaM,
            "240 s dio sigma ${larga.sigmaM} y 30 s dio ${corta.sigmaM}",
        )
    }

    @Test
    fun `la incertidumbre nunca baja del piso`() {
        val config = OccupationConfig(sigmaFloorM = 0.15)
        // Ruido practicamente nulo durante una hora: sin piso daria milimetros.
        val noise = Gaussian(3)
        val fixes = (0 until 3600).map { i ->
            noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L + i * 1000L, 0.6, noise)
        }
        val point = StaticPointEstimator.estimate(fixes, config)
        assertNotNull(point)
        assertTrue(point.sigmaM >= 0.15, "sigma ${point.sigmaM} quedo por debajo del piso")
    }

    @Test
    fun `descarta el calentamiento inicial`() {
        val config = OccupationConfig(warmupSec = 5.0)
        val noise = Gaussian(11)
        val fixes = (0 until 60).map { i ->
            // Los primeros cinco segundos estan corridos 30 m: solucion sin converger.
            val centre = if (i < 5) Vec3(30.0, 0.0, 0.0) else Vec3.ZERO
            noisyFix(frame, centre, 1_700_000_000_000L + i * 1000L, 4.0, noise)
        }
        val point = StaticPointEstimator.estimate(fixes, config)
        assertNotNull(point)
        val error = horizontalDistance(frame.toGeo(Vec3.ZERO), point.geo)
        assertTrue(error < 1.5, "el calentamiento corrio el centro $error m")
    }

    @Test
    fun `sin muestras devuelve null`() {
        assertNull(StaticPointEstimator.estimate(emptyList()))
    }

    @Test
    fun `sin muestras de calidad suficiente devuelve null`() {
        val noise = Gaussian(5)
        val fixes = (0 until 60).map { i ->
            noisyFix(frame, Vec3.ZERO, 1_700_000_000_000L + i * 1000L, 40.0, noise, satsUsed = 3)
        }
        assertNull(StaticPointEstimator.estimate(fixes))
    }

    @Test
    fun `acepta fixes de iOS que no informan satelites`() {
        val noise = Gaussian(9)
        val fixes = (0 until 90).map { i ->
            noisyFix(
                frame, Vec3.ZERO, 1_700_000_000_000L + i * 1000L, 5.0, noise,
                satsUsed = null, meanCn0DbHz = null,
            )
        }
        val point = StaticPointEstimator.estimate(fixes)
        assertNotNull(point)
        assertNull(point.satsUsed)
        assertNull(point.meanCn0DbHz)
    }

    @Test
    fun `la distancia entre dos puntos ocupados se mide con su incertidumbre combinada`() {
        val origin = StaticPointEstimator.estimate(occupation(seed = 1, outliers = 0))
        val landing = StaticPointEstimator.estimate(
            occupation(seed = 2, outliers = 0, truth = Vec3(150.0, 160.0, 0.0))
        )
        assertNotNull(origin)
        assertNotNull(landing)

        // 150 este y 160 norte dan 219,32 m de horizontal.
        val truth = sqrt(150.0 * 150.0 + 160.0 * 160.0)
        val measured = horizontalDistance(origin.geo, landing.geo)
        val sigma = combinedSigma(origin, landing)

        assertTrue(
            abs(measured - truth) < 3.0,
            "medi $measured m contra $truth m verdaderos",
        )
        assertTrue(sigma > origin.sigmaM, "la sigma combinada tiene que superar a cada una")
        assertEquals(
            sqrt(origin.sigmaM * origin.sigmaM + landing.sigmaM * landing.sigmaM),
            sigma,
            absoluteTolerance = 1e-9,
        )
    }
}
