package ar.com.longcasting.core.gnss

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuente de posicion. Es una interfaz comun y no un `expect class` a proposito: cada plataforma
 * aporta su implementacion y se inyecta en el grafo, de modo que los tests pueden reproducir
 * trazas grabadas sin tocar codigo de plataforma ni usar mocks.
 *
 * Implementaciones: `AndroidLocationEngine` (LocationManager + GnssStatus),
 * `IosLocationEngine` (CLLocationManager) y `ReplayLocationEngine` (tests).
 */
interface LocationEngine {
    val fixes: Flow<Fix>
    val quality: StateFlow<GnssQuality>

    /** Empieza a pedir posiciones a 1 Hz. Idempotente. */
    fun start()

    /** Libera el receptor. Idempotente. */
    fun stop()
}
