package ar.com.longcasting

import androidx.compose.runtime.staticCompositionLocalOf
import ar.com.longcasting.ads.AdsService
import ar.com.longcasting.core.gnss.LocationEngine
import ar.com.longcasting.data.SessionRepository
import ar.com.longcasting.data.SettingsRepository
import ar.com.longcasting.data.ShooterRepository
import ar.com.longcasting.data.SinkerRepository
import ar.com.longcasting.data.ThrowRepository
import ar.com.longcasting.db.LongcastingDatabase

/**
 * Grafo de dependencias, armado a mano. Son pocas piezas y ninguna necesita ciclo de vida
 * propio, asi que un contenedor de inyeccion solo agregaria magia: los tests construyen esto
 * mismo con un driver en memoria y un motor de posicion de reproduccion.
 */
class AppGraph(
    val database: LongcastingDatabase,
    val locationEngine: LocationEngine,
    val exporter: FileExporter,
    val feedback: Feedback,
    val ads: AdsService,
) {
    val shooters = ShooterRepository(database)
    val sinkers = SinkerRepository(database)
    val sessions = SessionRepository(database)
    val throws = ThrowRepository(database)
    val settings = SettingsRepository(database)
}

/** Entrega un archivo generado al sistema (compartir, guardar). */
interface FileExporter {
    suspend fun share(fileName: String, mimeType: String, content: String): Boolean
}

/**
 * Aviso al terminar una ocupacion. Importa mas de lo que parece: cuando estas agachado sobre
 * la plomada no vas a estar mirando la pantalla.
 */
interface Feedback {
    fun success()
    fun warning()
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("No hay AppGraph provisto en la composicion")
}
