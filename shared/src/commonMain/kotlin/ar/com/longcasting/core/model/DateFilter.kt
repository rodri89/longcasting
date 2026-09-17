package ar.com.longcasting.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Rango de tiempo semiabierto: incluye [fromInclusive] y excluye [toExclusive].
 *
 * Es semiabierto a proposito. Un dia va de las 00:00 a las 00:00 del siguiente sin solaparse
 * con el, y asi no hay que restarle un milisegundo al final ni preocuparse por un tiro
 * registrado exactamente a medianoche.
 */
data class MillisRange(val fromInclusive: Long, val toExclusive: Long)

/** Los tiros se filtran por dia local, no por UTC: importa el dia en que se tiro. */
sealed interface DateFilter {
    data object Todo : DateFilter
    data object Hoy : DateFilter
    data object UltimosSieteDias : DateFilter
    data class Dia(val date: LocalDate) : DateFilter
}

/**
 * Rango que le corresponde al filtro, o null si no hay que filtrar nada.
 *
 * La zona horaria entra por parametro (con el default del sistema) para que los tests no
 * dependan de la zona de la maquina donde corren.
 */
@OptIn(ExperimentalTime::class)
fun DateFilter.range(
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): MillisRange? = when (this) {
    DateFilter.Todo -> null
    DateFilter.Hoy -> dayRange(localDate(nowMillis, zone), zone)
    DateFilter.UltimosSieteDias -> {
        val today = localDate(nowMillis, zone)
        MillisRange(
            // Siete dias contando hoy, no hoy mas siete.
            fromInclusive = today.minus(6, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
            toExclusive = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
        )
    }
    is DateFilter.Dia -> dayRange(date, zone)
}

@OptIn(ExperimentalTime::class)
fun localDate(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

fun dayRange(date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()) = MillisRange(
    fromInclusive = date.atStartOfDayIn(zone).toEpochMilliseconds(),
    toExclusive = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
)

/** Dias que tienen al menos un tiro, del mas reciente al mas viejo. */
fun daysWithThrows(
    timestamps: List<Long>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<LocalDate> = timestamps.map { localDate(it, zone) }.distinct().sortedDescending()

/** "04/09" — etiqueta corta para los chips de fecha. */
fun LocalDate.shortLabel(): String =
    "${day.toString().padStart(2, '0')}/${(month.ordinal + 1).toString().padStart(2, '0')}"
