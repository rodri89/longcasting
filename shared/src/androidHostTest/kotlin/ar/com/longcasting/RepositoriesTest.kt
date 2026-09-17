package ar.com.longcasting

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.com.longcasting.core.geo.EnuFrame
import ar.com.longcasting.core.geo.Geo
import ar.com.longcasting.core.gnss.Fix
import ar.com.longcasting.core.measure.StaticPoint
import ar.com.longcasting.core.measure.toOfficialCm
import ar.com.longcasting.core.model.MillisRange
import ar.com.longcasting.core.model.daysWithThrows
import ar.com.longcasting.data.AddOutcome
import ar.com.longcasting.data.NewThrow
import ar.com.longcasting.data.SessionRepository
import ar.com.longcasting.data.SettingsRepository
import ar.com.longcasting.data.ShooterRepository
import ar.com.longcasting.data.SinkerRepository
import ar.com.longcasting.data.ThrowRepository
import ar.com.longcasting.data.createDatabase
import ar.com.longcasting.data.nowMillis
import ar.com.longcasting.data.throwsToCsv
import ar.com.longcasting.db.LongcastingDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Se corre contra SQLite de verdad en memoria, no contra dobles: lo que hay que verificar es
 * el esquema y las consultas, y un mock del DAO no probaria ninguna de las dos cosas.
 */
class RepositoriesTest {

    /** Atajo para los tests que solo necesitan el id de un alta que tiene que ser nueva. */
    private suspend fun ShooterRepository.addOrFail(name: String): Long {
        val outcome = add(name)
        return (outcome as? AddOutcome.Created)?.id
            ?: error("esperaba crear \"$name\" y dio $outcome")
    }

    /**
     * Base en memoria con su driver a mano. El driver hace falta para un par de casos que
     * necesitan SQL crudo (retrasar la fecha de un tiro, mirar una columna que ninguna query
     * expone); meter esas consultas en el esquema solo para los tests seria peor.
     */
    private class TestDb(val database: LongcastingDatabase, private val driver: SqlDriver) {
        fun exec(sql: String) = driver.execute(null, sql, 0)

        fun queryLong(sql: String): Long? = driver.executeQuery(null, sql, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        }, 0).value
    }

    private fun testDb(): TestDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LongcastingDatabase.Schema.create(driver)
        return TestDb(createDatabase(driver), driver)
    }

    private fun database(): LongcastingDatabase = testDb().database

    private fun point(geo: Geo, sigma: Double = 0.8) = StaticPoint(
        geo = geo,
        sigmaM = sigma,
        samplesAccepted = 100,
        samplesRejected = 4,
        durationSec = 60.0,
        spreadRmsM = 1.6,
        meanCn0DbHz = 38.0,
        satsUsed = 11,
        hasL5 = true,
    )

    private fun fix(geo: Geo, t: Long) = Fix(
        tMillis = t,
        lat = geo.lat,
        lon = geo.lon,
        altM = geo.altM,
        accuracyM = 4.0,
        satsUsed = 11,
    )

    // `shooter.name` y `sinker.grams` tienen indice unico. Antes de estos casos, un alta
    // repetida tiraba SQLiteConstraintException adentro de una corrutina y mataba la app.

    @Test
    fun `agregar un tirador repetido avisa en vez de reventar`() = runTest {
        val shooters = ShooterRepository(database())
        val created = shooters.addOrFail("Ana")

        assertEquals(AddOutcome.Duplicate(created), shooters.add("Ana"))
        // Mayusculas y espacios sobrantes no crean una segunda "Ana".
        assertEquals(AddOutcome.Duplicate(created), shooters.add("  ana  "))
        assertEquals(AddOutcome.Duplicate(created), shooters.add("ANA"))
        assertEquals(1, shooters.observeAll().first().size)
    }

    @Test
    fun `un nombre en blanco no se da de alta`() = runTest {
        val shooters = ShooterRepository(database())
        assertEquals(AddOutcome.Invalid, shooters.add("   "))
        assertTrue(shooters.observeAll().first().isEmpty())
    }

    @Test
    fun `re-agregar un tirador quitado lo restaura con sus tiros`() = runTest {
        val db = database()
        val shooters = ShooterRepository(db)
        val throws = ThrowRepository(db)
        val id = shooters.addOrFail("Beto")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()
        throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = id, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = 200.0, gpsSigmaM = 1.0, pathLengthM = null,
                tapeDistanceCm = null, officialCm = 20000, bearingDeg = null,
            )
        )

        shooters.archive(id)
        assertTrue(shooters.observeAll().first().isEmpty())

        // Un archivado sigue ocupando el nombre por el indice unico, asi que volver a
        // cargarlo tiene que devolver el mismo tirador, no chocar contra una fila invisible.
        assertEquals(AddOutcome.Restored(id), shooters.add("Beto"))
        assertEquals(listOf("Beto"), shooters.observeAll().first().map { it.name })
        assertEquals(1, throws.observeHistory(shooterId = id).first().size)
    }

    @Test
    fun `agregar una plomada repetida avisa en vez de reventar`() = runTest {
        val sinkers = SinkerRepository(database())
        val cien = sinkers.observeAll().first().first { it.grams == 100L }

        assertEquals(AddOutcome.Duplicate(cien.id), sinkers.add(100, "100 g", null))
        assertEquals(AddOutcome.Invalid, sinkers.add(0, "0 g", null))
        assertEquals(4, sinkers.observeAll().first().size)

        val nueva = sinkers.add(200, "200 g", 0.40)
        assertTrue(nueva is AddOutcome.Created)
        assertEquals(5, sinkers.observeAll().first().size)
    }

    @Test
    fun `re-agregar una plomada quitada la restaura`() = runTest {
        val sinkers = SinkerRepository(database())
        val cien = sinkers.observeAll().first().first { it.grams == 100L }

        sinkers.archive(cien.id)
        assertEquals(3, sinkers.observeAll().first().size)

        assertEquals(AddOutcome.Restored(cien.id), sinkers.add(100, "100 g", null))
        val restaurada = sinkers.observeAll().first().first { it.grams == 100L }
        assertEquals(cien.id, restaurada.id)
        // Conserva el diametro minimo de hilo del reglamento, no el que se paso al re-agregar.
        assertEquals(0.25, restaurada.minLineMm)
    }

    @Test
    fun `el origen se guarda una vez y lo comparten todos los tiros de la jornada`() = runTest {
        val tdb = testDb()
        val db = tdb.database
        val sessions = SessionRepository(db)
        val throws = ThrowRepository(db)
        val shooterId = ShooterRepository(db).addOrFail("jona")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = sessions.currentSession()

        val vertice = Geo(-38.0055, -57.5426, 8.0)
        val originId = sessions.setOrigin(sessionId, point(vertice), listOf(fix(vertice, 1L)))

        repeat(3) { i ->
            throws.save(
                NewThrow(
                    sessionId = sessionId, shooterId = shooterId, sinkerId = sinkerId,
                    originPointId = originId, landing = point(vertice),
                    gpsDistanceM = 200.0 + i, gpsSigmaM = 1.0, pathLengthM = null,
                    tapeDistanceCm = null, officialCm = 20000 + i, bearingDeg = null,
                )
            )
        }

        assertEquals(3, throws.observeHistory().first().size)
        // Un solo punto de origen para los tres tiros. Los de caida si son uno por tiro: en
        // total 1 + 3 puntos, no 3 + 3 como pasaba cuando el origen se re-guardaba en cada uno.
        assertEquals(4L, tdb.queryLong("SELECT count(*) FROM measured_point"))
        assertEquals(1L, tdb.queryLong("SELECT count(DISTINCT originPointId) FROM throw_record"))
        assertEquals(originId, tdb.queryLong("SELECT DISTINCT originPointId FROM throw_record"))
        assertEquals(originId, sessions.observeOrigin().first()?.pointId)
    }

    @Test
    fun `cambiar el origen pisa el anterior de la jornada`() = runTest {
        val db = database()
        val sessions = SessionRepository(db)
        val sessionId = sessions.currentSession()

        val primero = sessions.setOrigin(sessionId, point(Geo(-38.0, -57.5, 8.0)), emptyList())
        val segundo = sessions.setOrigin(sessionId, point(Geo(-38.1, -57.6, 9.0), sigma = 0.4), emptyList())

        assertTrue(segundo != primero)
        val origin = sessions.observeOrigin().first()
        assertEquals(segundo, origin?.pointId)
        assertEquals(0.4, origin?.point?.sigmaM)
    }

    @Test
    fun `la jornada se reusa el mismo dia y rueda al siguiente`() = runTest {
        val db = database()
        val sessions = SessionRepository(db)

        val hoy = sessions.currentSession("Hoy")
        assertEquals(hoy, sessions.currentSession("Hoy"))

        // Se fuerza una sesion abierta de ayer, que es lo que pasaria al volver a la cancha
        // al dia siguiente sin haber cerrado nada.
        db.sessionQueries.close(nowMillis(), hoy)
        db.sessionQueries.insert(
            name = "Ayer",
            startedAt = nowMillis() - 36 * 60 * 60 * 1000L,
            windNote = null,
            notes = null,
        )
        val ayer = db.sessionQueries.lastInsertedId().executeAsOne()

        val nueva = sessions.currentSession("Hoy de nuevo")
        assertTrue(nueva != ayer, "una sesion de ayer no puede seguir siendo la de hoy")
        // Y el origen de ayer ya no aplica.
        assertEquals(null, sessions.observeOrigin().first())
    }

    @Test
    fun `el historial y el ranking respetan el rango de fechas`() = runTest {
        val tdb = testDb()
        val db = tdb.database
        val throws = ThrowRepository(db)
        val ana = ShooterRepository(db).addOrFail("Ana")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()

        suspend fun record(cm: Int) = throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = ana, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = cm / 100.0, gpsSigmaM = 1.0, pathLengthM = null,
                tapeDistanceCm = null, officialCm = cm, bearingDeg = null,
            )
        )
        val viejo = record(19000)
        record(21000)

        // Se corre un tiro al pasado para poder filtrar por rango.
        val hace10Dias = nowMillis() - 10 * 24 * 60 * 60 * 1000L
        tdb.exec("UPDATE throw_record SET createdAt = $hace10Dias WHERE id = $viejo")

        val ultimaSemana = MillisRange(nowMillis() - 7 * 24 * 60 * 60 * 1000L, nowMillis() + 1000)
        assertEquals(2, throws.observeHistory().first().size)
        assertEquals(1, throws.observeHistory(range = ultimaSemana).first().size)
        assertEquals(21000, throws.observeRanking(range = ultimaSemana).first().single().bestCm)
        // Sin filtro, la mejor marca sigue siendo la de siempre.
        assertEquals(21000, throws.observeRanking().first().single().bestCm)
    }

    @Test
    fun `el ranking ordena por mejor marca y separa por plomada`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val shooters = ShooterRepository(db)
        val ana = shooters.addOrFail("Ana")
        val beto = shooters.addOrFail("Beto")
        val sinkers = SinkerRepository(db).observeAll().first()
        val cien = sinkers.first { it.grams == 100L }.id
        val ciento50 = sinkers.first { it.grams == 150L }.id
        val sessionId = SessionRepository(db).currentSession()

        suspend fun record(shooter: Long, sinker: Long, cm: Int) = throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = shooter, sinkerId = sinker,
                originPointId = null, landing = null,
                gpsDistanceM = cm / 100.0, gpsSigmaM = 1.0, pathLengthM = null,
                tapeDistanceCm = null, officialCm = cm, bearingDeg = null,
            )
        )
        record(ana, cien, 19000)
        record(ana, cien, 22000)
        record(beto, cien, 20500)
        record(beto, ciento50, 25000)
        val anulado = record(ana, ciento50, 30000)
        throws.setValidity(anulado, valid = false, reason = "Pisó la pedana")

        val ranking = throws.observeRanking().first()
        // Primero por peso de plomada, y adentro de cada peso de mayor a menor marca.
        assertEquals(
            listOf(100L to "Ana", 100L to "Beto", 150L to "Beto"),
            ranking.map { it.sinkerGrams to it.shooterName },
        )
        assertEquals(22000, ranking.first().bestCm)
        assertEquals(2, ranking.first().throwCount)

        // Filtrando por plomada queda el podio de esa plomada solamente.
        val soloCien = throws.observeRanking(sinkerId = cien).first()
        assertEquals(listOf("Ana", "Beto"), soloCien.map { it.shooterName })
    }

    @Test
    fun `un tiro rapido queda marcado y se distingue en el ranking`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val ana = ShooterRepository(db).addOrFail("Ana")
        val beto = ShooterRepository(db).addOrFail("Beto")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()

        suspend fun record(shooter: Long, cm: Int, quick: Boolean) = throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = shooter, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = cm / 100.0, gpsSigmaM = if (quick) 7.1 else 1.4,
                pathLengthM = null, tapeDistanceCm = null, officialCm = cm,
                bearingDeg = null, quickMode = quick,
            )
        )
        record(ana, 21000, quick = false)
        record(ana, 24000, quick = true)
        record(beto, 22000, quick = false)

        val history = throws.observeHistory().first()
        assertEquals(setOf(true, false), history.map { it.quickMode }.toSet())

        val ranking = throws.observeRanking().first()
        val deAna = ranking.first { it.shooterName == "Ana" }
        val deBeto = ranking.first { it.shooterName == "Beto" }
        // La mejor marca de Ana es la rapida, y el ranking tiene que poder decirlo: si no,
        // 240 m tomados con un fix suelto encabezan el podio como si fueran una medicion buena.
        assertEquals(24000, deAna.bestCm)
        assertTrue(deAna.bestWasQuick, "la mejor de Ana fue rapida y no quedo marcada")
        assertTrue(!deBeto.bestWasQuick)
    }

    @Test
    fun `los dias con tiros salen de las marcas de tiempo guardadas`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val ana = ShooterRepository(db).addOrFail("Ana")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()
        repeat(2) {
            throws.save(
                NewThrow(
                    sessionId = sessionId, shooterId = ana, sinkerId = sinkerId,
                    originPointId = null, landing = null,
                    gpsDistanceM = 200.0, gpsSigmaM = 1.0, pathLengthM = null,
                    tapeDistanceCm = null, officialCm = 20000, bearingDeg = null,
                )
            )
        }
        assertEquals(2, throws.observeThrowTimestamps().first().size)
        // Los dos tiros son de hoy, asi que el filtro ofrece un solo dia.
        assertEquals(1, daysWithThrows(throws.observeThrowTimestamps().first()).size)
    }

    @Test
    fun `las cuatro plomadas del reglamento se cargan solas`() = runTest {
        val sinkers = SinkerRepository(database()).observeAll().first()
        assertEquals(listOf(100L, 125L, 150L, 175L), sinkers.map { it.grams })
        assertEquals(
            listOf(0.25, 0.28, 0.31, 0.35),
            sinkers.map { it.minLineMm },
        )
    }

    @Test
    fun `la carga inicial no se repite al reabrir la base`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LongcastingDatabase.Schema.create(driver)
        createDatabase(driver)
        val second = createDatabase(driver)
        assertEquals(4, SinkerRepository(second).observeAll().first().size)
    }

    @Test
    fun `un tiro guardado vuelve del historial con los nombres resueltos`() = runTest {
        val db = database()
        val shooters = ShooterRepository(db)
        val sinkers = SinkerRepository(db)
        val sessions = SessionRepository(db)
        val throws = ThrowRepository(db)

        val shooterId = shooters.addOrFail("Rodrigo")
        val sinkerId = sinkers.observeAll().first().first { it.grams == 150L }.id
        val sessionId = sessions.currentSession("Prueba")

        val origin = Geo(-38.0055, -57.5426, 8.0)
        val landing = EnuFrame(origin).toGeo(ar.com.longcasting.core.geo.Vec3(120.0, 160.0, 0.0))
        val distance = 200.0
        // El origen se guarda una vez para la jornada, con su traza.
        val originPointId = sessions.setOrigin(
            sessionId, point(origin), listOf(fix(origin, 1L), fix(origin, 2L)),
        )

        throws.save(
            NewThrow(
                sessionId = sessionId,
                shooterId = shooterId,
                sinkerId = sinkerId,
                originPointId = originPointId,
                landing = point(landing),
                gpsDistanceM = distance,
                gpsSigmaM = 1.13,
                pathLengthM = 206.0,
                tapeDistanceCm = null,
                officialCm = toOfficialCm(distance),
                bearingDeg = 36.87,
                landingFixes = listOf(fix(landing, 3L)),
                trackFixes = listOf(fix(origin, 4L), fix(landing, 5L)),
            )
        )

        val history = throws.observeHistory().first()
        assertEquals(1, history.size)
        val row = history.single()
        assertEquals("Rodrigo", row.shooterName)
        assertEquals("150 g", row.sinkerLabel)
        assertEquals(20000, row.officialCm)
        assertTrue(row.valid)
        assertTrue(!row.measuredWithTape)

        // La traza cruda queda guardada entera para poder reprocesar el tiro mas adelante.
        assertEquals(5L, db.gnssFixQueries.countAll().executeAsOne())
    }

    @Test
    fun `la medida de cinta pasa a ser la oficial`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val shooterId = ShooterRepository(db).addOrFail("Ana")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()

        val id = throws.save(
            NewThrow(
                sessionId = sessionId,
                shooterId = shooterId,
                sinkerId = sinkerId,
                originPointId = null,
                landing = null,
                gpsDistanceM = 198.4,
                gpsSigmaM = 1.2,
                pathLengthM = null,
                tapeDistanceCm = null,
                officialCm = toOfficialCm(198.4),
                bearingDeg = null,
            )
        )
        assertEquals(19840, throws.observeHistory().first().single().officialCm)

        throws.setTapeDistance(id, tapeCm = 19912, gpsDistanceM = 198.4)
        val withTape = throws.observeHistory().first().single()
        assertEquals(19912, withTape.officialCm)
        assertEquals(19912, withTape.tapeDistanceCm)
        assertTrue(withTape.measuredWithTape)
        // La del GPS no se pierde: queda al lado para poder comparar.
        assertEquals(198.4, withTape.gpsDistanceM)

        // Al quitarla vuelve a valer la del GPS.
        throws.setTapeDistance(id, tapeCm = null, gpsDistanceM = 198.4)
        assertEquals(19840, throws.observeHistory().first().single().officialCm)
    }

    @Test
    fun `las mejores marcas ignoran los tiros invalidados`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val shooterId = ShooterRepository(db).addOrFail("Ana")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()

        suspend fun record(cm: Int) = throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = shooterId, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = cm / 100.0, gpsSigmaM = 1.0, pathLengthM = null,
                tapeDistanceCm = null, officialCm = cm, bearingDeg = null,
            )
        )

        record(19000)
        val best = record(23000)
        record(21000)
        assertEquals(23000, throws.observeRanking().first().single().bestCm)

        throws.setValidity(best, valid = false, reason = "Pisó la pedana")
        val bests = throws.observeRanking().first().single()
        assertEquals(21000, bests.bestCm)
        assertEquals(2, bests.throwCount)
    }

    @Test
    fun `el historial filtra por tirador`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val shooters = ShooterRepository(db)
        val ana = shooters.addOrFail("Ana")
        val beto = shooters.addOrFail("Beto")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()

        suspend fun record(shooter: Long, cm: Int) = throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = shooter, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = cm / 100.0, gpsSigmaM = 1.0, pathLengthM = null,
                tapeDistanceCm = null, officialCm = cm, bearingDeg = null,
            )
        )
        record(ana, 20000)
        record(beto, 21000)
        record(ana, 22000)

        assertEquals(3, throws.observeHistory().first().size)
        val soloAna = throws.observeHistory(shooterId = ana).first()
        assertEquals(2, soloAna.size)
        assertTrue(soloAna.all { it.shooterName == "Ana" })
    }

    @Test
    fun `archivar un tirador lo saca de la lista pero le deja sus tiros`() = runTest {
        val db = database()
        val shooters = ShooterRepository(db)
        val throws = ThrowRepository(db)
        val id = shooters.addOrFail("Carlos")
        val sinkerId = SinkerRepository(db).observeAll().first().first().id
        val sessionId = SessionRepository(db).currentSession()
        throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = id, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = 200.0, gpsSigmaM = 1.0, pathLengthM = null,
                tapeDistanceCm = null, officialCm = 20000, bearingDeg = null,
            )
        )

        shooters.archive(id)
        assertTrue(shooters.observeAll().first().isEmpty())
        assertEquals("Carlos", throws.observeHistory().first().single().shooterName)
    }

    @Test
    fun `los ajustes sobreviven a releer la base`() = runTest {
        val db = database()
        val settings = SettingsRepository(db)
        assertEquals(60, settings.observe().first().occupationSeconds)

        settings.setOccupationSeconds(120)
        assertEquals(120, settings.observe().first().occupationSeconds)

        // Fuera de rango se acota en vez de guardarse tal cual.
        settings.setOccupationSeconds(5000)
        assertEquals(300, settings.observe().first().occupationSeconds)

        assertEquals(1.5, settings.observe().first().targetSigmaM)
        settings.setTargetSigma(0.8)
        assertEquals(0.8, settings.observe().first().targetSigmaM)
        settings.setTargetSigma(99.0)
        assertEquals(5.0, settings.observe().first().targetSigmaM)

        // El modo rapido se persiste: por eso la pantalla de medir tiene que avisarlo.
        assertTrue(!settings.observe().first().quickMode)
        settings.setQuickMode(true)
        assertTrue(settings.observe().first().quickMode)
        settings.setQuickMode(false)
        assertTrue(!settings.observe().first().quickMode)
    }

    @Test
    fun `una sesion abierta se reutiliza en vez de abrir otra`() = runTest {
        val sessions = SessionRepository(database())
        val first = sessions.currentSession("Jornada")
        val second = sessions.currentSession("Jornada")
        assertEquals(first, second)

        sessions.close(first)
        assertTrue(sessions.observeOpen().first() == null)
        assertTrue(sessions.currentSession("Otra") != first)
    }

    @Test
    fun `el CSV exportado trae las tres medidas por separado`() = runTest {
        val db = database()
        val throws = ThrowRepository(db)
        val shooterId = ShooterRepository(db).addOrFail("Ana")
        val sinkerId = SinkerRepository(db).observeAll().first().first { it.grams == 125L }.id
        val sessionId = SessionRepository(db).currentSession()
        val id = throws.save(
            NewThrow(
                sessionId = sessionId, shooterId = shooterId, sinkerId = sinkerId,
                originPointId = null, landing = null,
                gpsDistanceM = 198.4, gpsSigmaM = 1.25, pathLengthM = 205.0,
                tapeDistanceCm = null, officialCm = 19840, bearingDeg = 42.0,
            )
        )
        throws.setTapeDistance(id, 19912, 198.4)

        val csv = throwsToCsv(throws.observeHistory().first())
        val header = csv.lineSequence().first().split(";")
        assertTrue("gps_m" in header && "cinta_cm" in header && "oficial_cm" in header)

        val row = csv.lineSequence().drop(1).first().split(";")
        assertEquals("Ana", row[header.indexOf("tirador")])
        assertEquals("125", row[header.indexOf("plomada_g")])
        assertEquals("19912", row[header.indexOf("oficial_cm")])
        assertEquals("19912", row[header.indexOf("cinta_cm")])
        assertEquals("198,400", row[header.indexOf("gps_m")])
        assertNotNull(row[header.indexOf("recorrido_m")])
    }
}
