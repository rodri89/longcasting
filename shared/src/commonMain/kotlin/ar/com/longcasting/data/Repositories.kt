package ar.com.longcasting.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import ar.com.longcasting.core.gnss.Fix
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.measure.StaticPoint
import ar.com.longcasting.core.model.localDate
import ar.com.longcasting.core.model.CastingSession
import ar.com.longcasting.core.model.MillisRange
import ar.com.longcasting.core.model.RankingEntry
import ar.com.longcasting.core.model.Shooter
import ar.com.longcasting.core.model.Sinker
import ar.com.longcasting.core.model.ThrowSummary
import ar.com.longcasting.db.LongcastingDatabase
import ar.com.longcasting.db.SelectHistory
import ar.com.longcasting.db.SelectRanking
import ar.com.longcasting.db.Measured_point
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Resultado de agregar un tirador o una plomada.
 *
 * Existe porque tanto `shooter.name` como `sinker.grams` tienen indice unico: sin consultar
 * antes, un alta repetida tira SQLiteConstraintException adentro de una corrutina y se lleva
 * la app puesta. Y como "Quitar" archiva en vez de borrar, el nombre o el peso siguen
 * ocupados por una fila que ya no se ve en la lista, asi que el choque parece inexplicable.
 */
sealed interface AddOutcome {
    data class Created(val id: Long) : AddOutcome

    /** Ya existia archivado: vuelve a la lista con todo su historial intacto. */
    data class Restored(val id: Long) : AddOutcome

    /** Ya esta en la lista. */
    data class Duplicate(val id: Long) : AddOutcome

    data object Invalid : AddOutcome
}

class ShooterRepository(
    private val database: LongcastingDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observeAll(): Flow<List<Shooter>> =
        database.shooterQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.map { Shooter(id = it.id, name = it.name, notes = it.notes) }
        }

    suspend fun add(name: String, notes: String? = null): AddOutcome =
        withContext<AddOutcome>(dispatcher) {
            val cleanName = name.trim()
            if (cleanName.isBlank()) return@withContext AddOutcome.Invalid
            database.transactionWithResult {
                val existing = database.shooterQueries.selectByName(cleanName).executeAsOneOrNull()
                when {
                    existing == null -> {
                        database.shooterQueries.insert(
                            name = cleanName,
                            notes = notes?.trim()?.ifBlank { null },
                            createdAt = nowMillis(),
                        )
                        AddOutcome.Created(database.shooterQueries.lastInsertedId().executeAsOne())
                    }
                    // Se conserva el nombre original, no el que se acaba de tipear: restaurar
                    // no deberia renombrar a alguien por una diferencia de mayusculas.
                    existing.archived -> {
                        database.shooterQueries.restore(existing.id)
                        AddOutcome.Restored(existing.id)
                    }
                    else -> AddOutcome.Duplicate(existing.id)
                }
            }
        }

    suspend fun update(id: Long, name: String, notes: String?) = withContext(dispatcher) {
        database.shooterQueries.update(name.trim(), notes?.trim()?.ifBlank { null }, id)
    }

    /** No se borra: un tirador archivado desaparece de las listas pero sus tiros quedan. */
    suspend fun archive(id: Long) = withContext(dispatcher) {
        database.shooterQueries.archive(id)
    }
}

class SinkerRepository(
    private val database: LongcastingDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observeAll(): Flow<List<Sinker>> =
        database.sinkerQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.map { Sinker(id = it.id, grams = it.grams, label = it.label, minLineMm = it.minLineMm) }
        }

    suspend fun add(grams: Long, label: String, minLineMm: Double?): AddOutcome =
        withContext<AddOutcome>(dispatcher) {
            if (grams <= 0) return@withContext AddOutcome.Invalid
            database.transactionWithResult {
                val existing = database.sinkerQueries.selectByGrams(grams).executeAsOneOrNull()
                when {
                    existing == null -> {
                        database.sinkerQueries.insert(grams, label.trim(), minLineMm)
                        AddOutcome.Created(database.sinkerQueries.lastInsertedId().executeAsOne())
                    }
                    existing.archived -> {
                        database.sinkerQueries.restore(existing.id)
                        AddOutcome.Restored(existing.id)
                    }
                    else -> AddOutcome.Duplicate(existing.id)
                }
            }
        }

    suspend fun update(id: Long, grams: Long, label: String, minLineMm: Double?) =
        withContext(dispatcher) {
            database.sinkerQueries.update(grams, label.trim(), minLineMm, id)
        }

    suspend fun archive(id: Long) = withContext(dispatcher) {
        database.sinkerQueries.archive(id)
    }
}

class SessionRepository(
    private val database: LongcastingDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observeOpen(): Flow<CastingSession?> =
        database.sessionQueries.selectOpen().asFlow().mapToOneOrNull(dispatcher).map { row ->
            row?.let {
                CastingSession(it.id, it.name, it.startedAt, it.endedAt, it.windNote, it.notes)
            }
        }

    fun observeAll(): Flow<List<CastingSession>> =
        database.sessionQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.map { CastingSession(it.id, it.name, it.startedAt, it.endedAt, it.windNote, it.notes) }
        }

    suspend fun open(name: String?, windNote: String? = null): Long = withContext(dispatcher) {
        database.transactionWithResult {
            database.sessionQueries.insert(
                name = name?.trim()?.ifBlank { null },
                startedAt = nowMillis(),
                windNote = windNote?.trim()?.ifBlank { null },
                notes = null,
            )
            database.sessionQueries.lastInsertedId().executeAsOne()
        }
    }

    suspend fun close(id: Long) = withContext(dispatcher) {
        database.sessionQueries.close(nowMillis(), id)
    }

    /**
     * Sesion de hoy: reusa la abierta si arranco el mismo dia local, y si no la cierra y abre
     * una nueva.
     *
     * El corte por dia es lo que hace caducar el origen: una jornada es una salida a la
     * cancha, y el vertice de ayer no tiene por que valer hoy. Sin esto, la sesion abierta
     * quedaria viva para siempre y el origen nunca se pediria de nuevo.
     */
    suspend fun currentSession(
        name: String? = null,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long = withContext(dispatcher) {
        val now = nowMillis()
        val open = database.sessionQueries.selectOpen().executeAsOneOrNull()
        when {
            open == null -> open(name)
            localDate(open.startedAt, zone) == localDate(now, zone) -> open.id
            else -> {
                close(open.id)
                open(name)
            }
        }
    }

    fun observeOrigin(): Flow<SessionOrigin?> =
        database.sessionQueries.selectOpen().asFlow().mapToOneOrNull(dispatcher).map { session ->
            val pointId = session?.originPointId ?: return@map null
            database.measuredPointQueries.selectById(pointId).executeAsOneOrNull()
                ?.let { SessionOrigin(pointId, it.toStaticPoint(), it.createdAt) }
        }

    /** Fija el vertice de la jornada. Pisa el anterior si ya habia uno. */
    suspend fun setOrigin(sessionId: Long, point: StaticPoint, fixes: List<Fix>): Long =
        withContext(dispatcher) {
            database.transactionWithResult {
                val pointId = database.insertPoint(point)
                database.insertFixes(throwId = null, pointId = pointId, role = "origin", fixes = fixes)
                database.sessionQueries.setOrigin(pointId, sessionId)
                pointId
            }
        }
}

/** El vertice de la jornada, con el id del punto para que cada tiro lo referencie. */
data class SessionOrigin(
    val pointId: Long,
    val point: StaticPoint,
    val markedAt: Long,
)

class ThrowRepository(
    private val database: LongcastingDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observeHistory(
        shooterId: Long? = null,
        sinkerId: Long? = null,
        sessionId: Long? = null,
        range: MillisRange? = null,
    ): Flow<List<ThrowSummary>> =
        database.throwRecordQueries.selectHistory(
            shooterId = shooterId,
            sinkerId = sinkerId,
            sessionId = sessionId,
            fromMillis = range?.fromInclusive,
            toMillis = range?.toExclusive,
        ).asFlow().mapToList(dispatcher).map { rows -> rows.map(::toSummary) }

    fun observeRanking(
        shooterId: Long? = null,
        sinkerId: Long? = null,
        range: MillisRange? = null,
    ): Flow<List<RankingEntry>> =
        database.throwRecordQueries.selectRanking(
            shooterId = shooterId,
            sinkerId = sinkerId,
            fromMillis = range?.fromInclusive,
            toMillis = range?.toExclusive,
        ).asFlow().mapToList(dispatcher).map { rows -> rows.mapNotNull(::toRankingEntry) }

    /** Marcas de tiempo de todos los tiros, para saber que dias ofrecer en el filtro. */
    fun observeThrowTimestamps(): Flow<List<Long>> =
        database.throwRecordQueries.selectThrowTimestamps().asFlow().mapToList(dispatcher)

    /**
     * Guarda el tiro completo en una sola transaccion: el punto de caida, el registro del
     * tiro, y la traza cruda que permitira reprocesarlo mas adelante.
     *
     * El origen no se inserta aca: es de la jornada, se guardo una sola vez al marcarlo, y el
     * tiro solo lo referencia por id.
     */
    suspend fun save(record: NewThrow): Long = withContext(dispatcher) {
        database.transactionWithResult {
            val originId = record.originPointId
            val landingId = record.landing?.let { database.insertPoint(it) }

            database.throwRecordQueries.insert(
                sessionId = record.sessionId,
                shooterId = record.shooterId,
                sinkerId = record.sinkerId,
                originPointId = originId,
                landingPointId = landingId,
                gpsDistanceM = record.gpsDistanceM,
                gpsSigmaM = record.gpsSigmaM,
                pathLengthM = record.pathLengthM,
                tapeDistanceCm = record.tapeDistanceCm?.toLong(),
                officialCm = record.officialCm.toLong(),
                bearingDeg = record.bearingDeg,
                valid = record.valid,
                invalidReason = record.invalidReason,
                notes = record.notes,
                createdAt = nowMillis(),
                quickMode = record.quickMode,
            )
            val throwId = database.throwRecordQueries.lastInsertedId().executeAsOne()

            database.insertFixes(throwId, landingId, "landing", record.landingFixes)
            database.insertFixes(throwId, null, "track", record.trackFixes)
            throwId
        }
    }

    /** Carga la medida oficial tomada con cinta; a partir de ahi es la que vale. */
    suspend fun setTapeDistance(throwId: Long, tapeCm: Int?, gpsDistanceM: Double?) =
        withContext(dispatcher) {
            val official = tapeCm?.toLong()
                ?: gpsDistanceM?.let { ar.com.longcasting.core.measure.toOfficialCm(it).toLong() }
                ?: 0L
            database.throwRecordQueries.setTapeDistance(tapeCm?.toLong(), official, throwId)
        }

    suspend fun setValidity(throwId: Long, valid: Boolean, reason: String?) =
        withContext(dispatcher) {
            database.throwRecordQueries.setValidity(valid, reason, throwId)
        }

    suspend fun delete(throwId: Long) = withContext(dispatcher) {
        database.throwRecordQueries.delete(throwId)
    }

}

/**
 * Insercion de un punto promediado y de su traza cruda. Son extensiones de la base y no
 * metodos privados de un repositorio porque las usan dos caminos distintos: el origen de la
 * jornada y el punto de caida de cada tiro. Van sin `withContext` a proposito: siempre corren
 * dentro de una transaccion que ya abrio quien las llama.
 */
internal fun LongcastingDatabase.insertPoint(point: StaticPoint): Long {
    measuredPointQueries.insert(
        lat = point.geo.lat,
        lon = point.geo.lon,
        altM = point.geo.altM,
        sigmaM = point.sigmaM,
        samplesAccepted = point.samplesAccepted.toLong(),
        samplesRejected = point.samplesRejected.toLong(),
        durationSec = point.durationSec,
        spreadRmsM = point.spreadRmsM,
        meanCn0 = point.meanCn0DbHz,
        satsUsed = point.satsUsed?.toLong(),
        hasL5 = point.hasL5,
        createdAt = nowMillis(),
    )
    return measuredPointQueries.lastInsertedId().executeAsOne()
}

internal fun LongcastingDatabase.insertFixes(
    throwId: Long?,
    pointId: Long?,
    role: String,
    fixes: List<Fix>,
) {
    fixes.forEach { fix ->
        gnssFixQueries.insert(
            throwId = throwId,
            pointId = pointId,
            role = role,
            tMillis = fix.tMillis,
            lat = fix.lat,
            lon = fix.lon,
            altM = fix.altM,
            accuracyM = fix.accuracyM,
            satsUsed = fix.satsUsed?.toLong(),
            cn0 = fix.meanCn0DbHz,
            accepted = true,
        )
    }
}

internal fun Measured_point.toStaticPoint() = StaticPoint(
    geo = Geo(lat, lon, altM),
    sigmaM = sigmaM,
    samplesAccepted = samplesAccepted.toInt(),
    samplesRejected = samplesRejected.toInt(),
    durationSec = durationSec,
    spreadRmsM = spreadRmsM,
    meanCn0DbHz = meanCn0,
    satsUsed = satsUsed?.toInt(),
    hasL5 = hasL5,
)

/** Todo lo que hace falta para persistir un tiro terminado. */
data class NewThrow(
    val sessionId: Long,
    val shooterId: Long,
    val sinkerId: Long,
    /** El origen se guarda una vez por jornada; el tiro solo lo referencia. */
    val originPointId: Long?,
    val landing: StaticPoint?,
    val gpsDistanceM: Double?,
    val gpsSigmaM: Double?,
    val pathLengthM: Double?,
    val tapeDistanceCm: Int?,
    val officialCm: Int,
    val bearingDeg: Double?,
    val valid: Boolean = true,
    val invalidReason: String? = null,
    val notes: String? = null,
    /** Toma rapida: un solo fix por punto, sin promediar. */
    val quickMode: Boolean = false,
    val landingFixes: List<Fix> = emptyList(),
    val trackFixes: List<Fix> = emptyList(),
)

private fun toSummary(row: SelectHistory) = ThrowSummary(
    id = row.id,
    sessionId = row.sessionId,
    createdAt = row.createdAt,
    officialCm = row.officialCm.toInt(),
    gpsDistanceM = row.gpsDistanceM,
    gpsSigmaM = row.gpsSigmaM,
    pathLengthM = row.pathLengthM,
    tapeDistanceCm = row.tapeDistanceCm?.toInt(),
    bearingDeg = row.bearingDeg,
    valid = row.valid,
    invalidReason = row.invalidReason,
    notes = row.notes,
    shooterId = row.shooterId,
    shooterName = row.shooterName,
    sinkerId = row.sinkerId,
    sinkerLabel = row.sinkerLabel,
    sinkerGrams = row.sinkerGrams,
    quickMode = row.quickMode,
)

private fun toRankingEntry(row: SelectRanking): RankingEntry? {
    // `max()` sobre un grupo vacio da null; ese grupo no tiene marca que rankear.
    val best = row.bestCm ?: return null
    return RankingEntry(
        shooterId = row.shooterId,
        shooterName = row.shooterName,
        sinkerId = row.sinkerId,
        sinkerLabel = row.sinkerLabel,
        sinkerGrams = row.sinkerGrams,
        bestCm = best.toInt(),
        averageCm = (row.avgCm ?: 0.0).roundToInt(),
        throwCount = row.throwCount.toInt(),
        lastAt = row.lastAt ?: 0L,
        bestWasQuick = row.bestWasQuick,
    )
}
