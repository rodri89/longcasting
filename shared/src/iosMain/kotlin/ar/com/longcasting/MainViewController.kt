package ar.com.longcasting

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import ar.com.longcasting.ads.AdsService
import ar.com.longcasting.data.createDatabase
import ar.com.longcasting.db.LongcastingDatabase
import ar.com.longcasting.gnss.IosLocationEngine
import ar.com.longcasting.platform.IosFeedback
import ar.com.longcasting.platform.IosFileExporter
import platform.UIKit.UIViewController

fun MainViewController(adsService: AdsService): UIViewController {
    val graph = AppGraph(
        database = createDatabase(
            NativeSqliteDriver(LongcastingDatabase.Schema, "longcasting.db")
        ),
        locationEngine = IosLocationEngine(),
        exporter = IosFileExporter(),
        feedback = IosFeedback(),
        ads = adsService,
    )
    return ComposeUIViewController { App(graph) }
}
