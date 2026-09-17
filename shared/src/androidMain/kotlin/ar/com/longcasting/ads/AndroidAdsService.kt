package ar.com.longcasting.ads

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import ar.com.longcasting.db.LongcastingDatabase
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Implementacion Android de [AdsService], mismo estilo que `AndroidFileExporter`/`AndroidFeedback`:
 * una clase de mas, no un `expect/actual`.
 *
 * Pedir consentimiento y mostrar un intersticial necesitan una `Activity` viva, no alcanza con
 * el `Context` de la `Application`. Como la app tiene una unica Activity (Compose puro), se
 * guarda una referencia debil que `MainActivity` actualiza en `onStart`/`onStop`.
 */
class AndroidAdsService(
    private val application: Application,
    database: LongcastingDatabase,
) : AdsService {

    private val frequencyCap = InterstitialFrequencyCap(database)
    private var activityRef: WeakReference<Activity>? = null
    private var loadedInterstitial: InterstitialAd? = null

    private val isDebugBuild: Boolean =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Usar test IDs para evitar crashes. Cambiar a PROD_ cuando AdMob esté configurado. */
    val bannerAdUnitId: String = AdMobIds.Android.TEST_BANNER

    private val interstitialAdUnitId: String = AdMobIds.Android.TEST_INTERSTITIAL

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    override suspend fun initialize() {
        gatherConsent()
        // El SDK se inicializa siempre, haya o no consentimiento: el consentimiento decide solo
        // si los anuncios son personalizados, no si el SDK existe.
        MobileAds.initialize(application)
        preloadInterstitial()
    }

    /**
     * Formulario UMP (GDPR). Sin timeout, si el round-trip a los servidores de Google no
     * resuelve (sin red, portal cautivo), la app queda esperando para siempre.
     */
    private suspend fun gatherConsent() {
        val activity = activityRef?.get() ?: return
        withTimeoutOrNull(CONSENT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val params = ConsentRequestParameters.Builder().build()
                val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
                consentInformation.requestConsentInfoUpdate(
                    activity,
                    params,
                    {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                        }
                    },
                    {
                        if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                    },
                )
            }
        }
    }

    private fun preloadInterstitial() {
        val activity = activityRef?.get() ?: return
        InterstitialAd.load(
            activity,
            interstitialAdUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadedInterstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No-fill es lo normal, no la excepcion: se reintenta en el proximo cambio
                    // de pestana, no hay nada mas que hacer aca.
                    loadedInterstitial = null
                }
            },
        )
    }

    override fun maybeShowInterstitialOnTabChange() {
        val activity = activityRef?.get() ?: return
        if (!frequencyCap.canShowToday()) return
        val ad = loadedInterstitial ?: run {
            preloadInterstitial()
            return
        }
        loadedInterstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = preloadInterstitial()
            override fun onAdFailedToShowFullScreenContent(adError: AdError) = preloadInterstitial()
        }
        // show() puede lanzar de forma sincronica si el anuncio dejo de estar listo justo antes
        // de mostrarse: nunca debe propagarse, el cambio de pestana ya se hizo.
        runCatching { ad.show(activity) }
        frequencyCap.markShownToday()
    }

    private companion object {
        const val CONSENT_TIMEOUT_MS = 5_000L
    }
}
