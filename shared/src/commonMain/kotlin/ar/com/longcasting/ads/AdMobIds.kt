package ar.com.longcasting.ads

/**
 * IDs de AdMob. Puro dato, sin ninguna dependencia de plataforma, asi que vive en comun aunque
 * cada `AdsService` elija los suyos.
 *
 * Los IDs de test son publicos y de Google (https://developers.google.com/admob/android/test-ads,
 * https://developers.google.com/admob/ios/test-ads): siempre tienen fill y nunca son trafico
 * real. Los de produccion son placeholders hasta que existan las apps de Longcasting en la
 * consola de AdMob (una por plataforma) con sus propias ad units.
 *
 * NUNCA usar los IDs de produccion mientras se este desarrollando o probando: tocar los propios
 * anuncios reales, aunque sea sin querer, cuenta como "invalid traffic" para Google.
 */
object AdMobIds {

    object Android {
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
        const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"

        const val PROD_BANNER = "ca-app-pub-3758335156050794/3756185206"
        const val PROD_INTERSTITIAL = "ca-app-pub-3758335156050794/6125171551"
    }

    object Ios {
        const val APP_ID = "ca-app-pub-3940256099942544~1458002511"
        const val TEST_BANNER = "ca-app-pub-3940256099942544/2934735716"
        const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/4411468910"

        const val PROD_BANNER = "ca-app-pub-3758335156050794/9523517203"
        const val PROD_INTERSTITIAL = "ca-app-pub-3758335156050794/2582704332"
    }
}
