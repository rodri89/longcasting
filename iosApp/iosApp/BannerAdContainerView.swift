import SwiftUI
import GoogleMobileAds

/// Banner anclado, hermano nativo de `ComposeView` en `ContentView.swift` (no dentro del arbol
/// de Compose): en Android el banner se resuelve como un slot Composable con `AndroidView`, pero
/// del lado de Kotlin no hay forma de referenciar `BannerView` sin cinterop, asi que en iOS se
/// agrega afuera, como una vista mas del `VStack`.
struct BannerAdContainerView: UIViewRepresentable {
    let adUnitId: String

    func makeUIView(context: Context) -> BannerView {
        let width = UIScreen.main.bounds.width
        let banner = BannerView(adSize: largeAnchoredAdaptiveBanner(width: width))
        banner.adUnitID = adUnitId
        banner.rootViewController = topViewController()
        banner.load(Request())
        return banner
    }

    func updateUIView(_ uiView: BannerView, context: Context) {}

    private func topViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController
    }
}
