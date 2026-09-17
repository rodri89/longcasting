import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let adsService: SwiftAdsService

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(adsService: adsService)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    // El banner real vive aca, afuera del arbol de Compose: Kotlin no puede referenciar
    // `BannerView` sin cinterop, asi que se agrega como hermano nativo de `ComposeView`, anclado
    // abajo, igual que el slot `bannerContent` en el `Scaffold` del lado Android.
    private let adsService = SwiftAdsService()

    var body: some View {
        VStack(spacing: 0) {
            ComposeView(adsService: adsService)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            BannerAdContainerView(adUnitId: adsService.bannerAdUnitId)
        }
        .ignoresSafeArea()
        .onAppear {
            adsService.initialize { _ in }
        }
    }
}