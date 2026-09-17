package ar.com.longcasting.ads

/**
 * Puerta de entrada a AdMob desde el codigo compartido. El banner no esta aca: es una vista
 * nativa persistente y cada plataforma la resuelve por su cuenta (`AndroidView`/`AdView` en
 * Android, un `UIViewRepresentable` nativo en iOS), no algo que una interfaz Kotlin simple pueda
 * representar sin cinterop.
 */
interface AdsService {

    /**
     * Junta consentimiento (UMP + ATT en iOS) y arranca el SDK. Nunca bloquea el arranque de la
     * app: si el consentimiento no resuelve en un tiempo razonable, se sigue igual sin el, y el
     * SDK se inicializa siempre, haya o no consentimiento. El consentimiento decide solo si los
     * anuncios son personalizados.
     */
    suspend fun initialize()

    /**
     * Se llama al cambiar de pestana (una pestana distinta a la actual). Decide sola, puertas
     * adentro, si ya se mostro un intersticial hoy; si no corresponde mostrar nada, es un no-op.
     * Nunca lanza: un intersticial que no cargo (no-fill) no puede frenar la navegacion.
     */
    fun maybeShowInterstitialOnTabChange()
}
