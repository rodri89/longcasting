package ar.com.longcasting.data

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** "03/09 14:25", que es lo que hace falta ver en el historial de una jornada. */
@OptIn(ExperimentalTime::class)
fun formatDateTime(millis: Long): String {
    val local = Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(local.day.toString().padStart(2, '0'))
        append('/')
        append((local.month.ordinal + 1).toString().padStart(2, '0'))
        append(' ')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
    }
}

@OptIn(ExperimentalTime::class)
fun formatDate(millis: Long): String {
    val local = Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(local.day.toString().padStart(2, '0'))
        append('/')
        append((local.month.ordinal + 1).toString().padStart(2, '0'))
        append('/')
        append(local.year)
    }
}
