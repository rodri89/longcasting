package ar.com.longcasting

import ar.com.longcasting.core.model.DateFilter
import ar.com.longcasting.core.model.dayRange
import ar.com.longcasting.core.model.daysWithThrows
import ar.com.longcasting.core.model.localDate
import ar.com.longcasting.core.model.range
import ar.com.longcasting.core.model.shortLabel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La zona horaria entra por parametro en todas las funciones de fecha justamente para poder
 * fijarla aca: un test que dependiera de la zona de la maquina pasaria en Buenos Aires y
 * fallaria en un CI en UTC.
 */
class DateFilterTest {

    private val zone = TimeZone.of("America/Argentina/Buenos_Aires")

    private fun millis(date: String, time: String = "12:00"): Long =
        LocalDateTime.parse("${date}T$time:00").toInstant(zone).toEpochMilliseconds()

    private val hoy = LocalDate.parse("2026-09-04")
    private val ahora = millis("2026-09-04", "15:30")

    @Test
    fun `todo no filtra nada`() {
        assertNull(DateFilter.Todo.range(ahora, zone))
    }

    @Test
    fun `hoy va de medianoche a medianoche`() {
        val range = DateFilter.Hoy.range(ahora, zone)!!
        assertEquals(millis("2026-09-04", "00:00"), range.fromInclusive)
        assertEquals(millis("2026-09-05", "00:00"), range.toExclusive)
    }

    @Test
    fun `el borde de medianoche cae del lado correcto`() {
        val range = DateFilter.Hoy.range(ahora, zone)!!
        // Un tiro registrado exactamente a las 00:00 de hoy entra; el de las 00:00 de manana no.
        assertTrue(millis("2026-09-04", "00:00") >= range.fromInclusive)
        assertTrue(millis("2026-09-04", "23:59") < range.toExclusive)
        assertTrue(millis("2026-09-05", "00:00") >= range.toExclusive)
        assertTrue(millis("2026-09-03", "23:59") < range.fromInclusive)
    }

    @Test
    fun `siete dias cuenta hoy adentro`() {
        val range = DateFilter.UltimosSieteDias.range(ahora, zone)!!
        // Del 29/08 al 04/09 son siete dias contando hoy.
        assertEquals(millis("2026-08-29", "00:00"), range.fromInclusive)
        assertEquals(millis("2026-09-05", "00:00"), range.toExclusive)

        assertTrue(millis("2026-08-29", "00:01") >= range.fromInclusive)
        assertTrue(millis("2026-08-28", "23:59") < range.fromInclusive)
    }

    @Test
    fun `un dia puntual solo trae ese dia`() {
        val range = DateFilter.Dia(LocalDate.parse("2026-08-15")).range(ahora, zone)!!
        assertEquals(millis("2026-08-15", "00:00"), range.fromInclusive)
        assertEquals(millis("2026-08-16", "00:00"), range.toExclusive)
    }

    @Test
    fun `el rango de un dia no se solapa con el del siguiente`() {
        val uno = dayRange(LocalDate.parse("2026-09-04"), zone)
        val dos = dayRange(LocalDate.parse("2026-09-05"), zone)
        assertEquals(uno.toExclusive, dos.fromInclusive)
    }

    @Test
    fun `el dia local no es el dia UTC cerca de la medianoche`() {
        // 03/09 22:00 en Buenos Aires ya es 04/09 en UTC. El filtro tiene que decir 03/09.
        val tarde = millis("2026-09-03", "22:00")
        assertEquals(LocalDate.parse("2026-09-03"), localDate(tarde, zone))
        assertEquals(LocalDate.parse("2026-09-04"), localDate(tarde, TimeZone.UTC))
    }

    @Test
    fun `los dias con tiros salen sin repetir y del mas nuevo al mas viejo`() {
        val dias = daysWithThrows(
            listOf(
                millis("2026-09-04", "10:00"),
                millis("2026-09-04", "17:00"),
                millis("2026-08-30", "09:00"),
                millis("2026-09-01", "19:00"),
            ),
            zone,
        )
        assertEquals(
            listOf(
                LocalDate.parse("2026-09-04"),
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-08-30"),
            ),
            dias,
        )
    }

    @Test
    fun `sin tiros no hay dias que ofrecer`() {
        assertEquals(emptyList(), daysWithThrows(emptyList(), zone))
    }

    @Test
    fun `la etiqueta corta es dia y mes con cero adelante`() {
        assertEquals("04/09", hoy.shortLabel())
        assertEquals("01/01", LocalDate.parse("2026-01-01").shortLabel())
        assertEquals("31/12", LocalDate.parse("2026-12-31").shortLabel())
    }
}
