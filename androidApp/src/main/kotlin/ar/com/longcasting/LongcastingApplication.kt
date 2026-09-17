package ar.com.longcasting

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import ar.com.longcasting.ads.AndroidAdsService
import ar.com.longcasting.data.createDatabase
import ar.com.longcasting.db.LongcastingDatabase
import ar.com.longcasting.gnss.AndroidLocationEngine
import ar.com.longcasting.platform.AndroidFeedback
import ar.com.longcasting.platform.AndroidFileExporter

class LongcastingApplication : Application() {

    /**
     * El grafo vive en la Application y no en la Activity para que el receptor GNSS y la base
     * sobrevivan a los giros de pantalla. Una medicion en curso no se puede perder porque el
     * telefono cambio de orientacion en el medio de la cancha.
     */
    val graph: AppGraph by lazy {
        val driver = AndroidSqliteDriver(
            schema = LongcastingDatabase.Schema,
            context = this,
            name = "longcasting.db",
        )
        val database = createDatabase(driver)
        AppGraph(
            database = database,
            locationEngine = AndroidLocationEngine(this),
            exporter = AndroidFileExporter(this),
            feedback = AndroidFeedback(this),
            ads = AndroidAdsService(this, database),
        )
    }
}
