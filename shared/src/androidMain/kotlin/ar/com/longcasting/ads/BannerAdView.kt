package ar.com.longcasting.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import ar.com.longcasting.LocalAppGraph
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * Banner adaptativo anclado. Se llama desde `App.kt` como `bannerContent`, fuera de cualquier
 * scroll de pantalla. En iOS este slot queda vacio: el banner real se agrega nativo en
 * `ContentView.swift`, por afuera del arbol de Compose.
 */
@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    val adsService = LocalAppGraph.current.ads as? AndroidAdsService ?: return
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                adUnitId = adsService.bannerAdUnitId
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidthDp)
                )
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}
