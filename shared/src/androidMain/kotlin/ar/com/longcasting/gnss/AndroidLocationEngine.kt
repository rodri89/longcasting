package ar.com.longcasting.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import ar.com.longcasting.core.gnss.Fix
import ar.com.longcasting.core.gnss.GnssQuality
import ar.com.longcasting.core.gnss.LocationEngine
import ar.com.longcasting.core.gnss.QualityLevel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Receptor GNSS de Android.
 *
 * Usa `LocationManager` con `GPS_PROVIDER` a proposito, y **no** FusedLocationProvider: el
 * fusionado mezcla WiFi y celda, y aplica un suavizado que arruina el promedio de una
 * ocupacion estatica. Aca queremos la solucion cruda del receptor.
 *
 * `GnssStatus` aporta ademas los satelites usados, el CN0 medio y la deteccion de segunda
 * frecuencia (L5 / E5a), que es lo que dice si el equipo puede aspirar a la precision alta.
 */
class AndroidLocationEngine(private val context: Context) : LocationEngine {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val handler = Handler(Looper.getMainLooper())

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<Fix> = _fixes.asSharedFlow()

    private val _quality = MutableStateFlow(GnssQuality())
    override val quality: StateFlow<GnssQuality> = _quality.asStateFlow()

    private var running = false
    private var satsVisible: Int? = null
    private var satsUsed: Int? = null
    private var meanCn0: Double? = null
    private var hasL5 = false

    /**
     * Se implementan los cuatro metodos explicitamente: `onStatusChanged` y compania recien
     * pasaron a tener implementacion por defecto en API 30, y la app corre desde API 26.
     */
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publish(location)
        // Deprecado desde API 30, pero en API 26-29 sigue siendo abstracto y el sistema lo
        // invoca: sin la implementacion el dispositivo tira AbstractMethodError.
        @Deprecated("Solo hace falta para API 26-29", ReplaceWith(""))
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = refreshQuality()
        override fun onProviderDisabled(provider: String) = refreshQuality()
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var cn0Sum = 0.0
            var l5 = false
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    cn0Sum += status.getCn0DbHz(i).toDouble()
                }
                if (status.hasCarrierFrequencyHz(i) && isSecondFrequency(status.getCarrierFrequencyHz(i))) {
                    l5 = true
                }
            }
            satsVisible = status.satelliteCount
            satsUsed = used
            meanCn0 = if (used > 0) cn0Sum / used else null
            hasL5 = l5
            refreshQuality()
        }

        override fun onStopped() {
            satsUsed = null
            meanCn0 = null
            refreshQuality()
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        if (!hasPreciseLocation()) {
            refreshQuality()
            return
        }
        running = true
        locationManager.registerGnssStatusCallback(gnssCallback, handler)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            locationListener,
            Looper.getMainLooper(),
        )
        refreshQuality()
    }

    override fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        refreshQuality()
    }

    private fun publish(location: Location) {
        val fix = Fix(
            tMillis = location.time,
            lat = location.latitude,
            lon = location.longitude,
            altM = if (location.hasAltitude()) location.altitude else 0.0,
            // Sin precision declarada el fix no sirve para medir: se marca inutilizable
            // en vez de asumir que es bueno.
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 9_999.0,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            satsUsed = satsUsed,
            satsVisible = satsVisible,
            meanCn0DbHz = meanCn0,
            hasL5 = hasL5,
            provider = location.provider ?: LocationManager.GPS_PROVIDER,
        )
        _fixes.tryEmit(fix)
        _quality.value = _quality.value.copy(
            level = GnssQuality.fromAccuracy(fix.accuracyM),
            accuracyM = fix.accuracyM,
            satsUsed = satsUsed,
            satsVisible = satsVisible,
            meanCn0DbHz = meanCn0,
            hasL5 = hasL5,
            message = null,
        )
    }

    /** Vuelve a leer permisos y estado del proveedor. Llamarlo al volver del dialogo de permisos. */
    fun refreshQuality() {
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val enabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        _quality.value = _quality.value.copy(
            level = if (running) _quality.value.level else QualityLevel.NO_FIX,
            satsUsed = satsUsed,
            satsVisible = satsVisible,
            meanCn0DbHz = meanCn0,
            hasL5 = hasL5,
            locationPermissionGranted = coarse || fine,
            preciseLocationGranted = fine,
            locationEnabled = enabled,
            message = when {
                !fine && coarse ->
                    "Diste ubicacion aproximada. Para medir hace falta ubicacion precisa."
                !fine -> "Falta el permiso de ubicacion."
                !enabled -> "El GPS del telefono esta apagado."
                else -> null
            },
        )
    }

    private fun hasPermission(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasPreciseLocation() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private companion object {
        /**
         * Banda L5 / E5a / B2a: 1176,45 MHz. GPS L1 esta en 1575,42 y L2 en 1227,6, asi que
         * una portadora en esta ventana solo puede ser de segunda frecuencia.
         */
        fun isSecondFrequency(hz: Float) = hz in 1_164_000_000f..1_189_000_000f
    }
}
