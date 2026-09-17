package ar.com.longcasting.core.measure

import kotlin.math.ceil

/**
 * Reglamento: se registran metros y centimetros, y cualquier fraccion que supere el ultimo
 * centimetro entero se computa como 1 cm adicional. Es techo, no redondeo al mas cercano.
 *
 * El epsilon cubre el caso en que una distancia que deberia caer justo en un centimetro
 * entero llega con un resto binario por arriba (200.00000000000003 en vez de 200.0). Sin el,
 * `ceil` sumaria un centimetro inexistente. Un resto por debajo no molesta: `ceil` lo sube
 * al entero correcto igual.
 */
fun toOfficialCm(meters: Double): Int {
    require(meters >= 0.0) { "La distancia no puede ser negativa: $meters" }
    return ceil(meters * 100.0 - 1e-9).toInt()
}

/** Formatea centimetros oficiales como "213,45 m". */
fun formatOfficialCm(cm: Int): String {
    val m = cm / 100
    val rest = cm % 100
    return "$m,${rest.toString().padStart(2, '0')} m"
}
