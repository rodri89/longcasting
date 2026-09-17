package ar.com.longcasting.data

import app.cash.sqldelight.db.SqlDriver
import ar.com.longcasting.core.model.STANDARD_SINKERS
import ar.com.longcasting.db.LongcastingDatabase

/**
 * Cada plataforma crea su propio driver (AndroidSqliteDriver / NativeSqliteDriver) y lo pasa
 * aca. No hace falta expect/actual: el driver ya es la abstraccion.
 */
fun createDatabase(driver: SqlDriver): LongcastingDatabase {
    val database = LongcastingDatabase(driver)
    seedSinkers(database)
    return database
}

/** Las cuatro plomadas del reglamento se cargan solas la primera vez. */
private fun seedSinkers(database: LongcastingDatabase) {
    val queries = database.sinkerQueries
    if (queries.countAll().executeAsOne() > 0L) return
    database.transaction {
        STANDARD_SINKERS.forEach { (grams, label, minLineMm) ->
            queries.insert(grams = grams, label = label, minLineMm = minLineMm)
        }
    }
}
