package ar.com.longcasting.gnss

import ar.com.longcasting.core.gnss.Fix
import ar.com.longcasting.core.gnss.GnssQuality
import ar.com.longcasting.core.gnss.LocationEngine
import ar.com.longcasting.core.gnss.QualityLevel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLActivityTypeFitness
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.Foundation.NSError
// Los metodos de categoria de Objective-C llegan a Kotlin como extensiones: hay que importarlos.
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Receptor de iOS.
 *
 * CoreLocation solo entrega posicion ya procesada: no hay acceso a mediciones GNSS crudas, ni
 * a conteo de satelites, ni a CN0, ni forma de saber si el equipo esta siguiendo L5. Por eso
 * en iOS el techo de precision es mas bajo que en Android y esos campos van nulos: la
 * incertidumbre se deriva solo de `horizontalAccuracy` y del promediado estatico.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationEngine : LocationEngine {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<Fix> = _fixes.asSharedFlow()

    private val _quality = MutableStateFlow(GnssQuality())
    override val quality: StateFlow<GnssQuality> = _quality.asStateFlow()

    private var running = false

    private val manager = CLLocationManager()

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            (didUpdateLocations.lastOrNull() as? CLLocation)?.let(::publish)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            _quality.value = _quality.value.copy(
                level = QualityLevel.NO_FIX,
                message = didFailWithError.localizedDescription,
            )
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            refreshQuality()
        }
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = CLActivityTypeFitness
        // Sin esto el sistema pausa las actualizaciones cuando detecta que estas quieto,
        // que es justo lo que hacemos durante una ocupacion estatica.
        manager.pausesLocationUpdatesAutomatically = false
    }

    override fun start() {
        if (running) return
        running = true
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        refreshQuality()
    }

    override fun stop() {
        if (!running) return
        running = false
        manager.stopUpdatingLocation()
        refreshQuality()
    }

    private fun publish(location: CLLocation) {
        // horizontalAccuracy negativa significa que la posicion no es valida.
        val accuracy = location.horizontalAccuracy
        if (accuracy < 0.0) return

        val (lat, lon) = location.coordinate.useContents { latitude to longitude }
        val fix = Fix(
            tMillis = (location.timestamp.timeIntervalSince1970 * 1000.0).toLong(),
            lat = lat,
            lon = lon,
            altM = location.altitude,
            accuracyM = accuracy,
            speedMps = location.speed.takeIf { it >= 0.0 },
            satsUsed = null,
            satsVisible = null,
            meanCn0DbHz = null,
            hasL5 = false,
            provider = "corelocation",
        )
        _fixes.tryEmit(fix)
        _quality.value = _quality.value.copy(
            level = GnssQuality.fromAccuracy(accuracy),
            accuracyM = accuracy,
            message = null,
        )
    }

    fun refreshQuality() {
        val status: CLAuthorizationStatus = manager.authorizationStatus
        val granted = status == kCLAuthorizationStatusAuthorizedWhenInUse ||
            status == kCLAuthorizationStatusAuthorizedAlways
        // En iOS 14+ el usuario puede conceder ubicacion "reducida", que para medir no sirve.
        val precise = granted && manager.accuracyAuthorization ==
            CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy

        _quality.value = _quality.value.copy(
            level = if (running) _quality.value.level else QualityLevel.NO_FIX,
            locationPermissionGranted = granted,
            preciseLocationGranted = precise,
            locationEnabled = CLLocationManager.locationServicesEnabled(),
            message = when {
                !granted -> "Falta el permiso de ubicacion."
                !precise -> "Diste ubicacion aproximada. Para medir hace falta ubicacion precisa."
                else -> null
            },
        )
    }
}
