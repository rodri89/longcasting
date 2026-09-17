import Foundation
import UIKit
import AppTrackingTransparency
import GoogleMobileAds
import UserMessagingPlatform
import Shared

/// Implementacion iOS de `AdsService` (protocolo Kotlin exportado por el framework `Shared`).
/// Vive en Swift y no en `iosMain` porque el Google Mobile Ads SDK se agrega via Swift Package
/// Manager (sin CocoaPods) y no tiene bindings de cinterop para Kotlin/Native: no hay forma de
/// llamarlo directo desde Kotlin sin ese puente.
final class SwiftAdsService: NSObject, AdsService, FullScreenContentDelegate {

    private let frequencyCap = IosAdsSupportKt.createInterstitialFrequencyCap()
    private var loadedInterstitial: InterstitialAd?

    private var isDebugBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    private var interstitialAdUnitId: String {
        isDebugBuild ? AdMobIds.Ios.shared.TEST_INTERSTITIAL : AdMobIds.Ios.shared.PROD_INTERSTITIAL
    }

    /// Leido por `BannerAdContainerView`: test en debug, real en release.
    var bannerAdUnitId: String {
        isDebugBuild ? AdMobIds.Ios.shared.TEST_BANNER : AdMobIds.Ios.shared.PROD_BANNER
    }

    func initialize(completionHandler: @escaping (Error?) -> Void) {
        Task {
            await gatherConsent()
            // El SDK se inicializa siempre, haya o no consentimiento: el consentimiento decide
            // solo si los anuncios son personalizados, no si el SDK existe.
            await MobileAds.shared.start()
            preloadInterstitial()
            completionHandler(nil)
        }
    }

    /// ATT primero (sin el prompt de ATT, UMP no puede ofrecer consentimiento personalizado en
    /// iOS) y despues el formulario UMP (GDPR). Cada paso tiene su propio timeout: si el
    /// round-trip a los servidores de Google no resuelve (sin red, portal cautivo), la app sigue
    /// igual sin consentimiento en vez de quedarse esperando para siempre.
    private func gatherConsent() async {
        await withTimeoutOrNil(seconds: 5) {
            if #available(iOS 14, *) {
                _ = await ATTrackingManager.requestTrackingAuthorization()
            }
        }
        await withTimeoutOrNil(seconds: 5) {
            await self.requestConsentFormIfNeeded()
        }
    }

    private func requestConsentFormIfNeeded() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            ConsentInformation.shared.requestConsentInfoUpdate(with: RequestParameters()) { error in
                guard error == nil else {
                    continuation.resume()
                    return
                }
                ConsentForm.loadAndPresentIfRequired(from: nil) { _ in
                    continuation.resume()
                }
            }
        }
    }

    private func preloadInterstitial() {
        InterstitialAd.load(with: interstitialAdUnitId, request: Request()) { [weak self] ad, error in
            self?.loadedInterstitial = error == nil ? ad : nil
        }
    }

    func maybeShowInterstitialOnTabChange() {
        guard frequencyCap.canShowToday() else { return }
        guard let ad = loadedInterstitial else {
            preloadInterstitial()
            return
        }
        loadedInterstitial = nil
        ad.fullScreenContentDelegate = self
        // present(from:) usa el view controller de mas arriba si se pasa nil.
        ad.present(from: nil)
        frequencyCap.markShownToday()
    }

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        preloadInterstitial()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        preloadInterstitial()
    }
}

/// Corre `operation` con un tope de tiempo; si no termina antes, sigue igual sin esperar mas.
private func withTimeoutOrNil(seconds: TimeInterval, operation: @escaping () async -> Void) async {
    await withTaskGroup(of: Void.self) { group in
        group.addTask { await operation() }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
        await group.next()
        group.cancelAll()
    }
}
