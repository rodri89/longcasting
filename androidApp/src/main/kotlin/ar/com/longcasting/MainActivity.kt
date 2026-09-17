package ar.com.longcasting

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import ar.com.longcasting.ads.AndroidAdsService
import ar.com.longcasting.ads.BannerAdView
import ar.com.longcasting.gnss.AndroidLocationEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph by lazy { (application as LongcastingApplication).graph }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // El motor decide solo si quedo con permiso preciso o solo aproximado.
            (graph.locationEngine as? AndroidLocationEngine)?.refreshQuality()
            startEngineIfAllowed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Una ocupacion dura hasta cinco minutos con el telefono apoyado y sin tocar: si la
        // pantalla se apaga, el usuario pierde de vista el progreso.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val darkTheme = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        // Hace falta la Activity ya adjunta antes de inicializar: el formulario de consentimiento
        // UMP se presenta sobre ella, no alcanza con el Context de la Application.
        (graph.ads as? AndroidAdsService)?.attachActivity(this)
        lifecycleScope.launch { graph.ads.initialize() }

        setContent { App(graph, darkTheme = darkTheme, bannerContent = { BannerAdView() }) }
    }

    override fun onStart() {
        super.onStart()
        (graph.ads as? AndroidAdsService)?.attachActivity(this)
        val engine = graph.locationEngine as? AndroidLocationEngine
        if (engine != null && !engine.hasPreciseLocation()) {
            // Se piden las dos: en Android 12+ pedir solo la precisa muestra igual la opcion
            // de "aproximada", y con esa el proveedor GPS no entrega nada util.
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        } else {
            startEngineIfAllowed()
        }
    }

    override fun onStop() {
        super.onStop()
        (graph.ads as? AndroidAdsService)?.detachActivity(this)
    }

    private fun startEngineIfAllowed() {
        val engine = graph.locationEngine as? AndroidLocationEngine ?: return
        engine.refreshQuality()
        if (engine.hasPreciseLocation()) engine.start()
    }
}
