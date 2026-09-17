package ar.com.longcasting.core.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Toda la matematica de la app trabaja en un plano tangente local (ENU) anclado al vertice
 * de la cancha, en metros. Sobre distancias menores a un kilometro el error de esa proyeccion
 * es submilimetrico, asi que no hace falta ninguna formula de distancia esferica y las
 * operaciones (promediar, restar, medir) son aritmetica vectorial comun.
 */
object Wgs84 {
    /** Semieje mayor, en metros. */
    const val A = 6378137.0

    /** Achatamiento. */
    const val F = 1.0 / 298.257223563

    /** Semieje menor, en metros. */
    const val B = A * (1.0 - F)

    /** Primera excentricidad al cuadrado. */
    const val E2 = F * (2.0 - F)

    /** Segunda excentricidad al cuadrado. */
    const val EP2 = (A * A - B * B) / (B * B)
}

internal fun Double.toRadians() = this * PI / 180.0

internal fun Double.toDegrees() = this * 180.0 / PI

/** Coordenada geodesica WGS84. Latitud y longitud en grados, altura elipsoidal en metros. */
data class Geo(val lat: Double, val lon: Double, val altM: Double = 0.0)

/** Vector cartesiano en metros. Segun el contexto es ECEF (x, y, z) o ENU (este, norte, arriba). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)

    /** Norma en el plano horizontal. Es la que vale para medir un tiro. */
    val horizontalNorm: Double get() = sqrt(x * x + y * y)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

fun Geo.toEcef(): Vec3 {
    val latRad = lat.toRadians()
    val lonRad = lon.toRadians()
    val sinLat = sin(latRad)
    val cosLat = cos(latRad)
    val sinLon = sin(lonRad)
    val cosLon = cos(lonRad)
    val n = Wgs84.A / sqrt(1.0 - Wgs84.E2 * sinLat * sinLat)
    return Vec3(
        x = (n + altM) * cosLat * cosLon,
        y = (n + altM) * cosLat * sinLon,
        z = (n * (1.0 - Wgs84.E2) + altM) * sinLat,
    )
}

/** Inversa de [toEcef] por el metodo de Bowring: forma cerrada, sin iterar. */
fun Vec3.toGeo(): Geo {
    val r = sqrt(x * x + y * y)
    if (r < 1e-9) {
        // Sobre el eje polar. No es un caso real en una cancha, pero evita dividir por cero.
        val lat = if (z >= 0) 90.0 else -90.0
        return Geo(lat, 0.0, kotlin.math.abs(z) - Wgs84.B)
    }
    val theta = atan2(z * Wgs84.A, r * Wgs84.B)
    val sinTheta = sin(theta)
    val cosTheta = cos(theta)
    val latRad = atan2(
        z + Wgs84.EP2 * Wgs84.B * sinTheta * sinTheta * sinTheta,
        r - Wgs84.E2 * Wgs84.A * cosTheta * cosTheta * cosTheta,
    )
    val lonRad = atan2(y, x)
    val sinLat = sin(latRad)
    val n = Wgs84.A / sqrt(1.0 - Wgs84.E2 * sinLat * sinLat)
    val alt = r / cos(latRad) - n
    return Geo(latRad.toDegrees(), lonRad.toDegrees(), alt)
}

/**
 * Plano tangente local anclado en [origin]. Convierte coordenadas geodesicas a metros ENU
 * (este, norte, arriba) y de vuelta.
 */
class EnuFrame(val origin: Geo) {
    private val originEcef = origin.toEcef()
    private val sinLat = sin(origin.lat.toRadians())
    private val cosLat = cos(origin.lat.toRadians())
    private val sinLon = sin(origin.lon.toRadians())
    private val cosLon = cos(origin.lon.toRadians())

    fun toEnu(point: Geo): Vec3 {
        val d = point.toEcef() - originEcef
        return Vec3(
            x = -sinLon * d.x + cosLon * d.y,
            y = -sinLat * cosLon * d.x - sinLat * sinLon * d.y + cosLat * d.z,
            z = cosLat * cosLon * d.x + cosLat * sinLon * d.y + sinLat * d.z,
        )
    }

    fun toGeo(enu: Vec3): Geo {
        val d = Vec3(
            x = -sinLon * enu.x - sinLat * cosLon * enu.y + cosLat * cosLon * enu.z,
            y = cosLon * enu.x - sinLat * sinLon * enu.y + cosLat * sinLon * enu.z,
            z = cosLat * enu.y + sinLat * enu.z,
        )
        return (originEcef + d).toGeo()
    }

    /** Distancia horizontal desde el origen del plano hasta [point], en metros. */
    fun horizontalDistanceTo(point: Geo): Double = toEnu(point).horizontalNorm
}

/** Distancia horizontal entre dos puntos, en metros. */
fun horizontalDistance(from: Geo, to: Geo): Double = EnuFrame(from).horizontalDistanceTo(to)

/** Rumbo desde el norte, en grados en sentido horario (0 = norte, 90 = este). */
fun bearingDegrees(enu: Vec3): Double = (atan2(enu.x, enu.y).toDegrees() + 360.0) % 360.0

/** Rumbo desde [from] hacia [to], en grados desde el norte. */
fun bearingDegrees(from: Geo, to: Geo): Double = bearingDegrees(EnuFrame(from).toEnu(to))
