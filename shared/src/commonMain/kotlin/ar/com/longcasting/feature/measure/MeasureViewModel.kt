package ar.com.longcasting.feature.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.com.longcasting.AppGraph
import ar.com.longcasting.core.geo.bearingDegrees
import ar.com.longcasting.core.geo.horizontalDistance
import ar.com.longcasting.core.gnss.Fix
import ar.com.longcasting.core.gnss.GnssQuality
import ar.com.longcasting.core.measure.LiveState
import ar.com.longcasting.core.measure.LiveTracker
import ar.com.longcasting.core.measure.OccupationConfig
import ar.com.longcasting.core.measure.OccupationStop
import ar.com.longcasting.core.measure.OccupationTarget
import ar.com.longcasting.core.measure.decideOccupation
import ar.com.longcasting.core.measure.StaticPoint
import ar.com.longcasting.core.measure.StaticPointEstimator
import ar.com.longcasting.core.measure.combinedSigma
import ar.com.longcasting.core.measure.toOfficialCm
import ar.com.longcasting.core.model.Shooter
import ar.com.longcasting.core.model.Sinker
import ar.com.longcasting.data.AppSettings
import ar.com.longcasting.data.NewThrow
import ar.com.longcasting.data.SessionOrigin
import ar.com.longcasting.data.nowMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PointRole { ORIGIN, LANDING, CLOSURE }

data class OccupationProgress(
    val role: PointRole,
    val elapsedSec: Int,
    /** Tope de tiempo, no tiempo fijo: si la sigma llega al objetivo se corta antes. */
    val maxSec: Int,
    val accepted: Int,
    val rejected: Int,
    val sigmaM: Double?,
    val targetSigmaM: Double,
    val quick: Boolean = false,
) {
    /** Ya se alcanzo la precision buscada; la toma esta por cerrarse sola. */
    val targetReached: Boolean get() = sigmaM != null && sigmaM <= targetSigmaM

    /** Se puede cerrar antes de tiempo, pero no antes de tener algo que valga la pena. */
    val canFinishEarly: Boolean get() = elapsedSec >= MIN_EARLY_FINISH_SEC && accepted >= 8

    companion object {
        const val MIN_EARLY_FINISH_SEC = 15
    }
}

data class ThrowResult(
    val distanceM: Double,
    val sigmaM: Double,
    val officialCm: Int,
    val pathLengthM: Double,
    val bearingDeg: Double?,
    val origin: StaticPoint,
    val landing: StaticPoint,
    val savedThrowId: Long? = null,
    /**
     * Error de cierre: cuanto se aparta una segunda ocupacion del vertice respecto del origen
     * original. Es la medida empirica de la exactitud de la jornada.
     */
    val closureErrorM: Double? = null,
)

private data class MeasureInputs(
    val shooters: List<Shooter>,
    val sinkers: List<Sinker>,
    val settings: AppSettings,
    val origin: SessionOrigin?,
)

sealed interface MeasureStage {
    data object Idle : MeasureStage
    data class Occupying(val progress: OccupationProgress) : MeasureStage
    data class Walking(val live: LiveState) : MeasureStage
    data class Result(val value: ThrowResult) : MeasureStage
}

data class MeasureUiState(
    val stage: MeasureStage = MeasureStage.Idle,
    val quality: GnssQuality = GnssQuality(),
    val shooters: List<Shooter> = emptyList(),
    val sinkers: List<Sinker> = emptyList(),
    val selectedShooterId: Long? = null,
    val selectedSinkerId: Long? = null,
    val settings: AppSettings = AppSettings(),
    /** Vertice de la jornada. Se marca una vez y lo comparten todos los tiros del dia. */
    val origin: SessionOrigin? = null,
    val distanceToOriginM: Double? = null,
    val message: String? = null,
) {
    /** Se configura en Ajustes y se persiste; la pantalla de medir lo refleja en los botones. */
    val quickMode: Boolean get() = settings.quickMode

    val selectedShooter: Shooter? get() = shooters.firstOrNull { it.id == selectedShooterId }
    val selectedSinker: Sinker? get() = sinkers.firstOrNull { it.id == selectedSinkerId }
    val canStart: Boolean get() = selectedShooter != null && selectedSinker != null

    /**
     * Aviso de origen lejano. Es la red de contencion contra el peor error posible de la app:
     * llegar a otra cancha, no marcar el vertice y medir toda la tarde contra el de ayer.
     */
    val originIsFar: Boolean
        get() = (distanceToOriginM ?: 0.0) > FAR_FROM_ORIGIN_M

    companion object {
        const val FAR_FROM_ORIGIN_M = 50.0
    }
}

/** Tope de espera de un fix en modo rapido antes de darse por vencido. */
private const val QUICK_TIMEOUT_SECONDS = 15

class MeasureViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private var occupationJob: Job? = null
    private var walkingJob: Job? = null
    private var earlyFinish: CompletableDeferred<Unit>? = null
    private var lastStop: OccupationStop = OccupationStop.CONTINUE

    private var tracker: LiveTracker? = null
    private val trackFixes = mutableListOf<Fix>()

    init {
        graph.locationEngine.start()

        viewModelScope.launch {
            graph.locationEngine.quality.collect { quality ->
                _state.value = _state.value.copy(quality = quality)
            }
        }
        // Rueda la jornada apenas arranca, para que un origen de ayer no siga vigente hoy.
        viewModelScope.launch { graph.sessions.currentSession() }

        // Colector permanente: mientras no estemos midiendo, sirve para mostrar a que
        // distancia del origen guardado estas parado.
        viewModelScope.launch {
            graph.locationEngine.fixes.collect { fix ->
                val origin = _state.value.origin ?: return@collect
                _state.value = _state.value.copy(
                    distanceToOriginM = horizontalDistance(origin.point.geo, fix.geo)
                )
            }
        }
        viewModelScope.launch {
            combine(
                graph.shooters.observeAll(),
                graph.sinkers.observeAll(),
                graph.settings.observe(),
                graph.sessions.observeOrigin(),
            ) { shooters, sinkers, settings, origin ->
                MeasureInputs(shooters, sinkers, settings, origin)
            }
                .collect { (shooters, sinkers, settings, origin) ->
                    _state.value = _state.value.copy(
                        shooters = shooters,
                        sinkers = sinkers,
                        settings = settings,
                        origin = origin,
                        distanceToOriginM = if (origin == null) null else _state.value.distanceToOriginM,
                        // Preseleccion para que arrancar un tiro sea un solo toque.
                        selectedShooterId = _state.value.selectedShooterId
                            ?.takeIf { id -> shooters.any { it.id == id } }
                            ?: shooters.firstOrNull()?.id,
                        selectedSinkerId = _state.value.selectedSinkerId
                            ?.takeIf { id -> sinkers.any { it.id == id } }
                            ?: sinkers.firstOrNull()?.id,
                    )
                }
        }
    }

    fun selectShooter(id: Long) {
        _state.value = _state.value.copy(selectedShooterId = id)
    }

    fun selectSinker(id: Long) {
        _state.value = _state.value.copy(selectedSinkerId = id)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Marca el vertice de la jornada. Se hace una sola vez por salida: queda en la base y
     * todos los tiros del dia lo referencian, asi que "siguiente tiro" ya no vuelve a pedir
     * los 60 s de ocupacion.
     */
    fun markOrigin() {
        capture(PointRole.ORIGIN) { point, fixes ->
            viewModelScope.launch {
                val sessionId = graph.sessions.currentSession()
                graph.sessions.setOrigin(sessionId, point, fixes)
                _state.value = _state.value.copy(
                    stage = MeasureStage.Idle,
                    distanceToOriginM = 0.0,
                    message = if (_state.value.quickMode) {
                        "Origen fijado en modo rápido: ± ${round2(point.sigmaM)} m. " +
                            "Todos los tiros de hoy arrastran ese error."
                    } else {
                        "Origen fijado en ${point.durationSec.toInt()} s " +
                            "(± ${round2(point.sigmaM)} m, ${closingReason()}). " +
                            "Ya no hace falta volver a marcarlo en cada tiro."
                    },
                )
            }
        }
    }

    /** Empieza un tiro desde el origen ya guardado. Se aprieta en el vertice, tras tirar. */
    fun startThrow() {
        val origin = _state.value.origin
        if (origin == null) {
            _state.value = _state.value.copy(message = "Primero marca el origen.")
            return
        }
        if (!_state.value.canStart) {
            _state.value = _state.value.copy(message = "Elegi un tirador y una plomada.")
            return
        }
        trackFixes.clear()
        tracker = LiveTracker(origin.point.geo)
        startWalking()
    }

    private fun startWalking() {
        walkingJob?.cancel()
        walkingJob = viewModelScope.launch {
            graph.locationEngine.fixes.collect { fix ->
                trackFixes += fix
                val live = tracker?.onFix(fix) ?: return@collect
                _state.value = _state.value.copy(stage = MeasureStage.Walking(live))
            }
        }
    }

    /** Paso 3: quedarse quieto sobre la plomada y cerrar la medicion. */
    fun markLanding() {
        walkingJob?.cancel()
        walkingJob = null
        val origin = _state.value.origin ?: return
        capture(PointRole.LANDING) { landing, landingFixes ->
            finishThrow(origin, landing, landingFixes)
        }
    }

    /**
     * Verificacion de cierre: volver al vertice y re-ocupar. La diferencia contra el origen es
     * el error real de la jornada, que ningun calculo de incertidumbre puede darte.
     */
    fun verifyClosure() {
        val current = _state.value.stage as? MeasureStage.Result ?: return
        capture(PointRole.CLOSURE) { point, _ ->
            val errorM = horizontalDistance(current.value.origin.geo, point.geo)
            _state.value = _state.value.copy(
                stage = MeasureStage.Result(current.value.copy(closureErrorM = errorM)),
                message = "Error de cierre: ${(errorM * 100).toInt() / 100.0} m",
            )
        }
    }

    private fun finishThrow(origin: SessionOrigin, landing: StaticPoint, landingFixes: List<Fix>) {
        val originPoint = origin.point
        val distance = horizontalDistance(originPoint.geo, landing.geo)
        val sigma = combinedSigma(originPoint, landing)
        val path = tracker?.finishPath(landing.geo, landing.sigmaM) ?: 0.0
        val result = ThrowResult(
            distanceM = distance,
            sigmaM = sigma,
            officialCm = toOfficialCm(distance),
            pathLengthM = path,
            bearingDeg = bearingDegrees(originPoint.geo, landing.geo),
            origin = originPoint,
            landing = landing,
        )
        _state.value = _state.value.copy(
            stage = MeasureStage.Result(result),
            message = if (_state.value.quickMode) {
                "Tiro rápido: ± ${round2(sigma)} m. Queda marcado como tal en el historial."
            } else {
                "Caída fijada en ${landing.durationSec.toInt()} s: ${closingReason()}."
            },
        )

        viewModelScope.launch {
            val state = _state.value
            val shooterId = state.selectedShooterId ?: return@launch
            val sinkerId = state.selectedSinkerId ?: return@launch
            val sessionId = graph.sessions.currentSession()
            val id = graph.throws.save(
                NewThrow(
                    sessionId = sessionId,
                    shooterId = shooterId,
                    sinkerId = sinkerId,
                    originPointId = origin.pointId,
                    landing = landing,
                    gpsDistanceM = distance,
                    gpsSigmaM = sigma,
                    pathLengthM = path,
                    tapeDistanceCm = null,
                    officialCm = result.officialCm,
                    bearingDeg = result.bearingDeg,
                    quickMode = state.quickMode,
                    landingFixes = landingFixes,
                    trackFixes = trackFixes.toList(),
                )
            )
            val stage = _state.value.stage
            if (stage is MeasureStage.Result) {
                _state.value = _state.value.copy(
                    stage = MeasureStage.Result(stage.value.copy(savedThrowId = id))
                )
            }
        }
    }

    /** Carga la medida tomada con cinta; a partir de ahi es la que vale como oficial. */
    fun setTapeDistance(centimetres: Int?) {
        val stage = _state.value.stage as? MeasureStage.Result ?: return
        val throwId = stage.value.savedThrowId ?: return
        viewModelScope.launch {
            graph.throws.setTapeDistance(throwId, centimetres, stage.value.distanceM)
            _state.value = _state.value.copy(
                message = if (centimetres == null) {
                    "Se quito la medida de cinta; vuelve a valer la del GPS."
                } else {
                    "Medida de cinta guardada."
                }
            )
        }
    }

    fun markInvalid(reason: String) {
        val stage = _state.value.stage as? MeasureStage.Result ?: return
        val throwId = stage.value.savedThrowId ?: return
        viewModelScope.launch {
            graph.throws.setValidity(throwId, valid = false, reason = reason)
            _state.value = _state.value.copy(message = "Tiro marcado como invalido.")
        }
    }

    fun discardThrow() {
        val stage = _state.value.stage as? MeasureStage.Result
        val throwId = stage?.value?.savedThrowId
        viewModelScope.launch {
            if (throwId != null) graph.throws.delete(throwId)
            reset()
        }
    }

    /**
     * Como cerro la ultima toma. Sirve para que el tirador aprenda si su equipo llega a la
     * precision buscada o si siempre esta agotando el tiempo: en el segundo caso conviene
     * subir el objetivo o buscar mejor cielo, no seguir esperando de gusto.
     */
    private fun round2(value: Double): Double = (value * 100).toInt() / 100.0

    private fun closingReason(): String = when (lastStop) {
        OccupationStop.TARGET_REACHED -> "cerró al llegar a la precisión buscada"
        OccupationStop.TIMEOUT -> "se agotó el tiempo sin llegar al objetivo"
        OccupationStop.CONTINUE -> "cerrada a mano"
    }

    /** Vuelve a reposo listo para el siguiente tiro. El origen de la jornada **no** se toca. */
    fun reset() {
        occupationJob?.cancel()
        walkingJob?.cancel()
        occupationJob = null
        walkingJob = null
        tracker = null
        trackFixes.clear()
        _state.value = _state.value.copy(stage = MeasureStage.Idle)
    }

    /**
     * Corta la ocupacion antes de que se cumpla el tiempo, pero **sin** cancelar el job: hay
     * que dejar que corra el estimador con lo que se junto. Por eso es una senal y no un
     * `cancel()`.
     */
    fun finishOccupationEarly() {
        earlyFinish?.complete(Unit)
    }

    /** Aborta la ocupacion y vuelve al inicio, descartando lo que se haya juntado. */
    fun cancelOccupation() {
        occupationJob?.cancel()
        occupationJob = null
        _state.value = _state.value.copy(stage = MeasureStage.Idle)
    }

    private fun capture(role: PointRole, onDone: (StaticPoint, List<Fix>) -> Unit) {
        if (_state.value.quickMode) captureQuick(role, onDone) else occupy(role, onDone)
    }

    /**
     * Toma rapida: se usa el primer fix aceptable y listo, sin promediar.
     *
     * La incertidumbre que se guarda es la que declara el receptor, sin ninguna mejora
     * inventada: promediar es lo unico que la baja, y aca no se promedia. Un tiro asi queda
     * marcado en la base para que el ranking no lo confunda con una medicion buena.
     */
    private fun captureQuick(role: PointRole, onDone: (StaticPoint, List<Fix>) -> Unit) {
        occupationJob?.cancel()
        occupationJob = viewModelScope.launch {
            val settings = _state.value.settings
            var progress = OccupationProgress(
                role = role,
                elapsedSec = 0,
                maxSec = QUICK_TIMEOUT_SECONDS,
                accepted = 0,
                rejected = 0,
                sigmaM = null,
                targetSigmaM = settings.targetSigmaM,
                quick = true,
            )
            _state.value = _state.value.copy(stage = MeasureStage.Occupying(progress))

            val fix = withTimeoutOrNull(QUICK_TIMEOUT_SECONDS * 1000L) {
                graph.locationEngine.fixes.first { it.accuracyM <= settings.maxAccuracyM }
            }
            if (fix == null) {
                if (settings.hapticFeedback) graph.feedback.warning()
                _state.value = _state.value.copy(
                    stage = MeasureStage.Idle,
                    message = "No llegó ningún fix utilizable. Probá a cielo abierto.",
                )
                return@launch
            }

            val point = StaticPoint(
                geo = fix.geo,
                sigmaM = fix.accuracyM,
                samplesAccepted = 1,
                samplesRejected = 0,
                durationSec = 0.0,
                spreadRmsM = fix.accuracyM,
                meanCn0DbHz = fix.meanCn0DbHz,
                satsUsed = fix.satsUsed,
                hasL5 = fix.hasL5,
            )
            if (settings.hapticFeedback) graph.feedback.success()
            lastStop = OccupationStop.CONTINUE
            onDone(point, listOf(fix))
        }
    }

    private fun occupy(role: PointRole, onDone: (StaticPoint, List<Fix>) -> Unit) {
        occupationJob?.cancel()
        occupationJob = viewModelScope.launch {
            val settings = _state.value.settings
            val config = OccupationConfig(
                maxAccuracyM = settings.maxAccuracyM,
                minSats = settings.minSats,
            )
            val target = OccupationTarget(
                minSeconds = AppSettings.FLOOR_OCCUPATION_SECONDS,
                maxSeconds = settings.occupationSeconds,
                targetSigmaM = settings.targetSigmaM,
            )
            val collected = mutableListOf<Fix>()
            val startedAt = nowMillis()
            var stop = OccupationStop.CONTINUE
            var progress = OccupationProgress(
                role = role,
                elapsedSec = 0,
                maxSec = settings.occupationSeconds,
                accepted = 0,
                rejected = 0,
                sigmaM = null,
                targetSigmaM = settings.targetSigmaM,
            )
            _state.value = _state.value.copy(stage = MeasureStage.Occupying(progress))

            val ticker = launch {
                while (true) {
                    delay(250)
                    val elapsed = ((nowMillis() - startedAt) / 1000).toInt()
                    progress = progress.copy(elapsedSec = elapsed)
                    _state.value = _state.value.copy(stage = MeasureStage.Occupying(progress))
                }
            }

            // `collect` no termina solo. La ocupacion se cierra por tiempo cumplido (el
            // timeout cancela el scope interno) o porque el usuario adelanta el cierre
            // (la senal completa y salimos ordenadamente).
            val signal = CompletableDeferred<Unit>()
            earlyFinish = signal
            withTimeoutOrNull(settings.occupationSeconds * 1000L) {
                coroutineScope {
                    val collector = launch {
                        graph.locationEngine.fixes.collect { fix ->
                            collected += fix
                            val estimate = StaticPointEstimator.estimate(collected, config)
                            progress = progress.copy(
                                accepted = estimate?.samplesAccepted ?: 0,
                                rejected = estimate?.samplesRejected ?: 0,
                                sigmaM = estimate?.sigmaM,
                            )
                            _state.value =
                                _state.value.copy(stage = MeasureStage.Occupying(progress))

                            // Se corta sola apenas la solucion converge, en vez de esperar el
                            // tiempo completo al pedo.
                            stop = decideOccupation(
                                elapsedSec = (nowMillis() - startedAt) / 1000.0,
                                sigmaM = estimate?.sigmaM,
                                acceptedSamples = estimate?.samplesAccepted ?: 0,
                                target = target,
                            )
                            if (stop != OccupationStop.CONTINUE) signal.complete(Unit)
                        }
                    }
                    signal.await()
                    collector.cancel()
                }
            }
            earlyFinish = null
            ticker.cancel()

            val point = StaticPointEstimator.estimate(collected, config)
            if (point == null) {
                if (settings.hapticFeedback) graph.feedback.warning()
                _state.value = _state.value.copy(
                    stage = MeasureStage.Idle,
                    message = "No hubo senal suficiente para fijar el punto. Probá a cielo abierto.",
                )
                return@launch
            }
            if (settings.hapticFeedback) graph.feedback.success()
            lastStop = stop
            onDone(point, collected.toList())
        }
    }

    override fun onCleared() {
        graph.locationEngine.stop()
        super.onCleared()
    }
}
