package ar.com.longcasting

import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.geo.Vec3
import ar.com.longcasting.core.geo.bearingDegrees
import ar.com.longcasting.core.geo.horizontalDistance
import ar.com.longcasting.core.geo.toEcef
import ar.com.longcasting.core.geo.toGeo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeodesyTest {

    @Test
    fun `geodesica a ECEF y de vuelta conserva el punto`() {
        val points = listOf(
            TEST_ORIGIN,
            Geo(-38.0055, -57.5426, 8.0),
            Geo(0.0, 0.0, 0.0),
            Geo(60.1699, 24.9384, 120.0),
        )
        for (point in points) {
            val back = point.toEcef().toGeo()
            // Un microgrado de latitud son ~11 cm; se exige mucho menos que eso.
            assertTrue(abs(back.lat - point.lat) < 1e-9, "lat ${back.lat} vs ${point.lat}")
            assertTrue(abs(back.lon - point.lon) < 1e-9, "lon ${back.lon} vs ${point.lon}")
            assertTrue(abs(back.altM - point.altM) < 1e-4, "alt ${back.altM} vs ${point.altM}")
        }
    }

    @Test
    fun `ENU a geodesica y de vuelta conserva los metros`() {
        val frame = EnuFrame(TEST_ORIGIN)
        val offsets = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(150.0, 220.0, 3.0),
            Vec3(-300.0, 40.0, -12.0),
            Vec3(1000.0, -1000.0, 0.0),
        )
        for (offset in offsets) {
            val back = frame.toEnu(frame.toGeo(offset))
            assertTrue(abs(back.x - offset.x) < 1e-3, "este ${back.x} vs ${offset.x}")
            assertTrue(abs(back.y - offset.y) < 1e-3, "norte ${back.y} vs ${offset.y}")
            assertTrue(abs(back.z - offset.z) < 1e-3, "arriba ${back.z} vs ${offset.z}")
        }
    }

    @Test
    fun `la distancia horizontal ignora el desnivel`() {
        val frame = EnuFrame(TEST_ORIGIN)
        // 180 m al este, 240 m al norte: triangulo 3-4-5 escalado, la horizontal son 300 m.
        val target = frame.toGeo(Vec3(180.0, 240.0, 15.0))
        val distance = horizontalDistance(TEST_ORIGIN, target)
        assertTrue(abs(distance - 300.0) < 0.01, "esperaba 300 m, dio $distance")
    }

    @Test
    fun `la distancia es simetrica`() {
        val frame = EnuFrame(TEST_ORIGIN)
        val target = frame.toGeo(Vec3(212.0, -95.0, 0.0))
        val ida = horizontalDistance(TEST_ORIGIN, target)
        val vuelta = horizontalDistance(target, TEST_ORIGIN)
        assertTrue(abs(ida - vuelta) < 1e-4, "$ida vs $vuelta")
    }

    @Test
    fun `el rumbo se mide desde el norte en sentido horario`() {
        assertTrue(abs(bearingDegrees(Vec3(0.0, 100.0, 0.0)) - 0.0) < 1e-9)
        assertTrue(abs(bearingDegrees(Vec3(100.0, 0.0, 0.0)) - 90.0) < 1e-9)
        assertTrue(abs(bearingDegrees(Vec3(0.0, -100.0, 0.0)) - 180.0) < 1e-9)
        assertTrue(abs(bearingDegrees(Vec3(-100.0, 0.0, 0.0)) - 270.0) < 1e-9)
        assertTrue(abs(bearingDegrees(Vec3(100.0, 100.0, 0.0)) - 45.0) < 1e-9)
    }
}
