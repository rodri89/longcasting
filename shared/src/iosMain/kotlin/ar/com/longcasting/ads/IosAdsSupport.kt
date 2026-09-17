package ar.com.longcasting.ads

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import ar.com.longcasting.data.createDatabase
import ar.com.longcasting.db.LongcastingDatabase

/**
 * Punto de entrada para `SwiftAdsService`: arma su propio `InterstitialFrequencyCap` con una
 * conexion liviana a la misma base, en vez de reestructurar el armado de `AppGraph` en
 * `MainViewController.kt` (que se construye despues, no antes, de que Swift necesite esto).
 * SQLite soporta bien conexiones concurrentes para una tabla chica de una sola fila.
 */
fun createInterstitialFrequencyCap(): InterstitialFrequencyCap =
    InterstitialFrequencyCap(
        createDatabase(NativeSqliteDriver(LongcastingDatabase.Schema, "longcasting.db"))
    )
