package ar.com.longcasting

import ar.com.longcasting.core.measure.formatOfficialCm
import ar.com.longcasting.core.measure.toOfficialCm
import kotlin.test.Test
import kotlin.test.assertEquals

class RoundingTest {

    @Test
    fun `una fraccion por encima del centimetro entero suma un centimetro`() {
        assertEquals(201, toOfficialCm(2.001))
        assertEquals(201, toOfficialCm(2.009))
        assertEquals(21301, toOfficialCm(213.0001))
    }

    @Test
    fun `un centimetro exacto no suma nada`() {
        assertEquals(200, toOfficialCm(2.0))
        assertEquals(0, toOfficialCm(0.0))
        assertEquals(21300, toOfficialCm(213.0))
        assertEquals(12300, toOfficialCm(123.0))
    }

    @Test
    fun `el resto binario no inventa un centimetro`() {
        // 2.13 * 100 no da exactamente 213 en punto flotante; el resultado tiene que ser 213.
        assertEquals(213, toOfficialCm(2.13))
        assertEquals(29, toOfficialCm(0.29))
        assertEquals(1207, toOfficialCm(12.07))
        // Un valor que llega con resto binario por arriba tampoco debe subir de centimetro.
        assertEquals(200, toOfficialCm(2.0000000000000004))
    }

    @Test
    fun `nunca redondea hacia abajo`() {
        assertEquals(214, toOfficialCm(2.1301))
        assertEquals(1, toOfficialCm(0.0001))
    }

    @Test
    fun `el formato separa metros y centimetros`() {
        assertEquals("213,45 m", formatOfficialCm(21345))
        assertEquals("213,05 m", formatOfficialCm(21305))
        assertEquals("213,00 m", formatOfficialCm(21300))
        assertEquals("0,07 m", formatOfficialCm(7))
    }
}
