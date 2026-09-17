package ar.com.longcasting

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.com.longcasting.data.ShooterRepository
import ar.com.longcasting.data.ThrowRepository
import ar.com.longcasting.data.createDatabase
import ar.com.longcasting.db.LongcastingDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migraciones del esquema: v1 -> v2 agrega `session.originPointId`, v2 -> v3 agrega
 * `throw_record.quickMode`.
 *
 * En vez de escribir a mano el esquema viejo (que se desincronizaria del real en cuanto alguien
 * tocara un `.sq`), se crea el esquema actual, se le saca la columna con DROP COLUMN para
 * dejarlo con la forma de v1, y recien ahi se migra. Asi el resto de las tablas y los datos son
 * exactamente los de una base real de alguien que ya tenia la app instalada.
 */
class MigrationTest {

    private fun SqlDriver.exec(sql: String) = execute(null, sql, 0)

    private fun SqlDriver.queryString(sql: String): String? =
        executeQuery(null, sql, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 0).value

    private fun SqlDriver.queryLong(sql: String): Long? =
        executeQuery(null, sql, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        }, 0).value

    private fun v1Database(): SqlDriver {
        val driver = v2Database()
        driver.exec("ALTER TABLE session DROP COLUMN originPointId")
        driver.exec("PRAGMA user_version = 1")
        return driver
    }

    private fun v2Database(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LongcastingDatabase.Schema.create(driver)
        driver.exec("ALTER TABLE throw_record DROP COLUMN quickMode")
        driver.exec("PRAGMA user_version = 2")
        return driver
    }

    @Test
    fun `migrar de v1 a v2 no pierde datos`() = runTest {
        val driver = v1Database()
        val old = createDatabase(driver)
        val shooterId = ShooterRepository(old).add("jona")
        // La sesion se inserta con SQL crudo: la query generada ya menciona `originPointId`,
        // que en una base v1 todavia no existe. Es exactamente lo que veria una app vieja.
        driver.exec(
            "INSERT INTO session(name, startedAt, windNote) " +
                "VALUES ('Jornada vieja', 1700000000000, 'viento norte')"
        )

        LongcastingDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 3)

        // Los datos que ya estaban siguen ahi.
        assertEquals("jona", driver.queryString("SELECT name FROM shooter"))
        assertEquals("Jornada vieja", driver.queryString("SELECT name FROM session"))
        assertEquals("viento norte", driver.queryString("SELECT windNote FROM session"))
        assertEquals(4L, driver.queryLong("SELECT count(*) FROM sinker"))
        assertTrue(shooterId is ar.com.longcasting.data.AddOutcome.Created)

        // Y las columnas nuevas existen, con su valor por defecto.
        assertNull(driver.queryString("SELECT originPointId FROM session"))
        assertEquals(0L, driver.queryLong("SELECT count(*) FROM throw_record WHERE quickMode = 1"))
    }

    @Test
    fun `migrar de v2 a v3 conserva los tiros y los marca como no rapidos`() = runTest {
        val driver = v2Database()
        val db = createDatabase(driver)
        val shooterId = (ShooterRepository(db).add("Ana") as ar.com.longcasting.data.AddOutcome.Created).id
        val sinkerId = driver.queryLong("SELECT id FROM sinker LIMIT 1")!!
        driver.exec("INSERT INTO session(name, startedAt) VALUES ('Vieja', 1700000000000)")
        // Con SQL crudo: la query generada ya menciona quickMode, que en v2 no existe.
        driver.exec(
            "INSERT INTO throw_record(sessionId, shooterId, sinkerId, gpsDistanceM, " +
                "officialCm, valid, createdAt) VALUES (1, $shooterId, $sinkerId, 200.0, " +
                "20000, 1, 1700000000000)"
        )

        LongcastingDatabase.Schema.migrate(driver, oldVersion = 2, newVersion = 3)

        val throws = ThrowRepository(createDatabase(driver)).observeHistory().first()
        assertEquals(1, throws.size)
        assertEquals(20000, throws.single().officialCm)
        // Los tiros que ya existian son mediciones promediadas, no rapidas.
        assertTrue(!throws.single().quickMode)
    }

    @Test
    fun `sobre una base migrada se puede fijar el origen`() = runTest {
        val driver = v1Database()
        LongcastingDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 3)

        // La prueba de fuego: la funcionalidad nueva sobre una base que venia de la version
        // anterior, no sobre una recien creada.
        val database = createDatabase(driver)
        val sessions = ar.com.longcasting.data.SessionRepository(database)
        val sessionId = sessions.currentSession("Despues de migrar")

        val pointId = sessions.setOrigin(
            sessionId,
            point = ar.com.longcasting.core.measure.StaticPoint(
                geo = ar.com.longcasting.core.geo.Geo(-38.0055, -57.5426, 8.0),
                sigmaM = 0.9,
                samplesAccepted = 100,
                samplesRejected = 3,
                durationSec = 60.0,
                spreadRmsM = 1.8,
                meanCn0DbHz = 39.0,
                satsUsed = 12,
                hasL5 = true,
            ),
            fixes = emptyList(),
        )

        val origin = sessions.observeOrigin().first()
        assertEquals(pointId, origin?.pointId)
        assertEquals(0.9, origin?.point?.sigmaM)
        assertEquals(true, origin?.point?.hasL5)
    }
}
