package ar.com.longcasting.data

import ar.com.longcasting.core.model.ThrowSummary

/**
 * Exportacion del historial. Se incluyen tanto la medida oficial como la del GPS y la de
 * cinta por separado, ademas de la incertidumbre: sin esas columnas los datos no se pueden
 * auditar despues.
 */
fun throwsToCsv(rows: List<ThrowSummary>): String {
    val header = listOf(
        "fecha", "tirador", "plomada_g", "oficial_cm", "oficial_m",
        "gps_m", "gps_sigma_m", "cinta_cm", "recorrido_m", "rumbo_deg",
        "valido", "motivo", "notas",
    )
    return buildString {
        appendLine(header.joinToString(";"))
        rows.forEach { row ->
            appendLine(
                listOf(
                    formatDateTime(row.createdAt),
                    row.shooterName,
                    row.sinkerGrams.toString(),
                    row.officialCm.toString(),
                    decimal(row.officialCm / 100.0, 2),
                    row.gpsDistanceM?.let { decimal(it, 3) } ?: "",
                    row.gpsSigmaM?.let { decimal(it, 2) } ?: "",
                    row.tapeDistanceCm?.toString() ?: "",
                    row.pathLengthM?.let { decimal(it, 1) } ?: "",
                    row.bearingDeg?.let { decimal(it, 1) } ?: "",
                    if (row.valid) "si" else "no",
                    row.invalidReason.orEmpty(),
                    row.notes.orEmpty(),
                ).joinToString(";") { escape(it) }
            )
        }
    }
}

/** Coma decimal, que es lo que espera una planilla en español. */
private fun escape(value: String): String =
    if (value.any { it == ';' || it == '\n' || it == '"' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

fun decimal(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(value * factor).toLong()
    val whole = scaled / factor
    val fraction = kotlin.math.abs(scaled % factor)
    if (decimals == 0) return whole.toString()
    val sign = if (value < 0 && whole == 0L) "-" else ""
    return "$sign$whole,${fraction.toString().padStart(decimals, '0')}"
}
