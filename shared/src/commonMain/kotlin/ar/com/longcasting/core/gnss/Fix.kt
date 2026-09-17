package ar.com.longcasting.core.gnss

import ar.com.longcasting.core.geo.Geo

/**
 * Una solucion de posicion entregada por el receptor.
 *
 * Los campos que Android expone y iOS no ([satsUsed], [satsVisible], [meanCn0DbHz], [hasL5])
 * son nulos o false en iOS: CoreLocation solo entrega posicion ya procesada.
 */
data class Fix(
    val tMillis: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    /** Error horizontal declarado por el receptor, en metros (aproximadamente al 68%). */
    val accuracyM: Double,
    val speedMps: Double? = null,
    val satsUsed: Int? = null,
    val satsVisible: Int? = null,
    val meanCn0DbHz: Double? = null,
    /** El receptor esta siguiendo senales de segunda frecuencia (L5 / E5a). */
    val hasL5: Boolean = false,
    val provider: String = "gps",
) {
    val geo: Geo get() = Geo(lat, lon, altM)
}

enum class QualityLevel { NO_FIX, POOR, FAIR, GOOD, EXCELLENT }

/** Estado del receptor, para la barra de calidad y la pantalla de diagnostico. */
data class GnssQuality(
    val level: QualityLevel = QualityLevel.NO_FIX,
    val accuracyM: Double? = null,
    val satsUsed: Int? = null,
    val satsVisible: Int? = null,
    val meanCn0DbHz: Double? = null,
    val hasL5: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    /**
     * En Android 12+ el usuario puede conceder solo ubicacion aproximada. Con eso el proveedor
     * GPS no entrega nada util, asi que hay que distinguirlo de "sin permiso".
     */
    val preciseLocationGranted: Boolean = false,
    val locationEnabled: Boolean = true,
    val message: String? = null,
) {
    companion object {
        fun fromAccuracy(accuracyM: Double?): QualityLevel = when {
            accuracyM == null -> QualityLevel.NO_FIX
            accuracyM <= 3.0 -> QualityLevel.EXCELLENT
            accuracyM <= 6.0 -> QualityLevel.GOOD
            accuracyM <= 12.0 -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
    }
}
