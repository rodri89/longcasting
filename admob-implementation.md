# AdMob en React Native — guía de implementación

Guía portable para montar AdMob (banner + intersticial) en otro proyecto React Native, con
el código y las decisiones que ya están probadas en PadelMatch. Todo lo que dice **⚠️** es
un problema que ya nos mordió en producción, no una precaución teórica.

Referencia de versiones con las que esto funciona: `react-native 0.86`, `react-native-google-mobile-ads ^16.3.4`,
Hermes y New Architecture activadas.

---

## 1. Instalación

```bash
npm install react-native-google-mobile-ads
cd ios && pod install && cd ..
```

Requiere **rebuild nativo**. No alcanza con recargar el bundle: si intentás usar el módulo
sin recompilar vas a ver `Invariant Violation: TurboModuleRegistry.getEnforcing(...): 'RNGoogleMobileAdsModule' could not be found`.

⚠️ Ese mismo error rompe los tests de Jest si algún test monta un árbol que importa la
librería. Si tenés tests, agregá el mock en el setup de Jest:

```js
// jest/setup.js
jest.mock('react-native-google-mobile-ads', () => ({
  __esModule: true,
  default: () => ({
    initialize: jest.fn().mockResolvedValue([]),
    setRequestConfiguration: jest.fn().mockResolvedValue(undefined),
  }),
  AdsConsent: { gatherConsent: jest.fn().mockResolvedValue({}) },
  BannerAd: () => null,
  BannerAdSize: { BANNER: 'BANNER', ADAPTIVE_BANNER: 'ADAPTIVE_BANNER' },
  TestIds: { BANNER: 'test-banner', INTERSTITIAL: 'test-interstitial' },
  useInterstitialAd: () => ({
    isLoaded: false, isClosed: false, error: undefined,
    load: jest.fn(), show: jest.fn(),
  }),
}));
```

---

## 2. IDs de AdMob

En la consola de AdMob vas a tener dos cosas distintas que es fácil confundir:

| Qué | Formato | Dónde va |
| --- | --- | --- |
| **App ID** | `ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN` (con `~`) | Solo en la config nativa (manifest / Info.plist) |
| **Ad unit ID** | `ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN` (con `/`) | Solo en el código JS |

Son **distintos por plataforma**: creás la app dos veces en AdMob (una Android, una iOS) y
cada una tiene su App ID y sus ad units. El bloque del medio (el publisher ID, 16 dígitos)
es el mismo en las dos.

### `src/config/admob.ts`

```ts
import { Platform } from 'react-native';
import { TestIds } from 'react-native-google-mobile-ads';

export const ADMOB_APP_ID = Platform.select({
    ios: 'ca-app-pub-XXXXXXXXXXXXXXXX~IOS_APP_ID',
    android: 'ca-app-pub-XXXXXXXXXXXXXXXX~ANDROID_APP_ID',
    default: 'ca-app-pub-XXXXXXXXXXXXXXXX~ANDROID_APP_ID',
});

const PRODUCTION_BANNER_ID = Platform.select({
    ios: 'ca-app-pub-XXXXXXXXXXXXXXXX/IOS_BANNER_UNIT',
    android: 'ca-app-pub-XXXXXXXXXXXXXXXX/ANDROID_BANNER_UNIT',
    default: 'ca-app-pub-XXXXXXXXXXXXXXXX/ANDROID_BANNER_UNIT',
});

const PRODUCTION_INTERSTITIAL_ID = Platform.select({
    ios: 'ca-app-pub-XXXXXXXXXXXXXXXX/IOS_INTERSTITIAL_UNIT',
    android: 'ca-app-pub-XXXXXXXXXXXXXXXX/ANDROID_INTERSTITIAL_UNIT',
    default: 'ca-app-pub-XXXXXXXXXXXXXXXX/ANDROID_INTERSTITIAL_UNIT',
});

// En desarrollo se usan los ad units de prueba de Google, que siempre tienen fill.
// Con la unidad real un emulador o simulador suele devolver no-fill y no se puede
// verificar el flujo.
export const ADMOB_BANNER_ID = __DEV__ ? TestIds.BANNER : PRODUCTION_BANNER_ID;
export const ADMOB_INTERSTITIAL_ID = __DEV__
    ? TestIds.INTERSTITIAL
    : PRODUCTION_INTERSTITIAL_ID;

// Dispositivos que reciben anuncios de prueba en vez de reales (solo en desarrollo).
// OJO: 'EMULATOR' solo lo traduce Android (a AdRequest.DEVICE_ID_EMULATOR); en iOS se
// manda literal y no matchea nada. Para un dispositivo físico agregá el ID que imprime
// el SDK en consola la primera vez que pide un anuncio real.
export const ADMOB_TEST_DEVICE_IDS = ['EMULATOR'];
```

⚠️ **Nunca pruebes contra ad units reales.** Tocar tus propios anuncios de producción, aunque
sea sin querer en desarrollo, es "invalid traffic" y Google suspende cuentas por eso. El
switch `__DEV__` de arriba es la protección principal; los test device IDs son la segunda.

⚠️ En PadelMatch el banner **no** tenía el switch `__DEV__` mientras que el intersticial sí,
así que en desarrollo servía anuncios reales. Poné el switch en los dos.

---

## 3. Configuración nativa

### Android — `android/app/src/main/AndroidManifest.xml`

Dentro de `<application>`:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~ANDROID_APP_ID"
    tools:replace="android:value"/>
```

⚠️ El `tools:replace="android:value"` es obligatorio: la librería declara su propio
`meta-data` con un placeholder y sin eso el merge del manifest falla en build. Necesitás
además `xmlns:tools="http://schemas.android.com/tools"` en el `<manifest>` raíz.

⚠️ Si el App ID falta o está mal escrito, **la app crashea al arrancar** (no falla en
silencio): el SDK de Google Mobile Ads lanza en el `onCreate`.

### iOS — `ios/<App>/Info.plist`

```xml
<key>GADApplicationIdentifier</key>
<string>ca-app-pub-XXXXXXXXXXXXXXXX~IOS_APP_ID</string>

<key>NSUserTrackingUsageDescription</key>
<string>Usamos esto para mostrarte anuncios más relevantes. Podés seguir usando la app igual.</string>

<key>SKAdNetworkItems</key>
<array>
    <dict>
        <key>SKAdNetworkIdentifier</key>
        <string>cstr6suwn9.skadnetwork</string>
    </dict>
</array>
```

- `NSUserTrackingUsageDescription` es **obligatorio** si vas a pedir ATT. Sin esta clave,
  Apple rechaza el binario en review.
- `SKAdNetworkItems` es la lista de redes para atribución. `cstr6suwn9.skadnetwork` es la de
  Google; si sumás mediación, cada red agrega su identificador. La lista completa y
  actualizada la publica Google en su documentación.

---

## 4. Inicialización en `App.tsx`

```tsx
import mobileAds, { AdsConsent } from 'react-native-google-mobile-ads';
import { ADMOB_TEST_DEVICE_IDS } from './src/config/admob';

const CONSENT_TIMEOUT_MS = 5000;

// gatherConsent() hace un round-trip a los servidores de Google. Sin este tope,
// si esa promesa nunca resuelve (sin red, portal cautivo, endpoint bloqueado)
// la app queda trabada para siempre en el splash.
function withTimeout<T>(promise: Promise<T>, timeoutMs: number) {
  return Promise.race([
    promise,
    new Promise<void>(resolve => setTimeout(resolve, timeoutMs)),
  ]);
}

function App() {
  const [isAdmobReady, setIsAdmobReady] = useState(false);

  useEffect(() => {
    async function initializeAds() {
      try {
        // Formulario de consentimiento (GDPR) y prompt de ATT en iOS si corresponde.
        await withTimeout(AdsConsent.gatherConsent(), CONSENT_TIMEOUT_MS);
      } catch (error) {
        console.warn('[AdMob] Consent gathering failed:', error);
      }

      try {
        if (__DEV__) {
          await mobileAds().setRequestConfiguration({
            testDeviceIdentifiers: ADMOB_TEST_DEVICE_IDS,
          });
        }

        // El SDK se inicializa siempre. El consentimiento decide si los anuncios
        // son personalizados, pero si no se inicializa acá no se inicializa
        // nunca más en toda la vida de la app.
        await mobileAds().initialize();
      } catch (error) {
        console.warn('[AdMob] Initialization failed:', error);
      } finally {
        setIsAdmobReady(true); // seguir igual
      }
    }

    initializeAds();
  }, []);

  if (!isAdmobReady) {
    return <SplashScreen />;
  }

  return /* ... árbol normal de la app ... */;
}
```

Las tres decisiones que importan acá:

1. **El timeout no rechaza, resuelve.** Si `gatherConsent()` cuelga, la app arranca igual sin
   consentimiento en vez de quedarse en el splash para siempre. ⚠️ Sin esto, un usuario detrás
   de un portal cautivo de wifi no puede abrir la app.
2. **`finally { setIsAdmobReady(true) }`.** Todo el bloque de ads puede fallar y la app tiene
   que arrancar igual. Los anuncios nunca son razón para bloquear el producto.
3. **`initialize()` corre siempre**, haya o no consentimiento. El consentimiento decide si los
   anuncios son personalizados, no si el SDK existe.

---

## 5. Banner

### `src/components/AdBanner.tsx`

```tsx
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { BannerAd, BannerAdSize, TestIds } from 'react-native-google-mobile-ads';

import { ADMOB_BANNER_ID } from '../config/admob';

type AdBannerProps = {
    adUnitId?: string;
    size?: BannerAdSize;
};

export default function AdBanner({
    adUnitId,
    size = BannerAdSize.BANNER,
}: AdBannerProps) {
    const [isFailed, setIsFailed] = useState(false);
    const [isLoaded, setIsLoaded] = useState(false);

    // Si el anuncio no carga el componente desaparece por completo, en vez de
    // dejar un hueco gris permanente en el layout.
    if (isFailed) {
        return null;
    }

    return (
        <View style={styles.container}>
            {!isLoaded && (
                <View style={styles.placeholder}>
                    <Text style={styles.placeholderText}>Anuncio</Text>
                </View>
            )}
            <BannerAd
                size={size}
                unitId={adUnitId ?? ADMOB_BANNER_ID ?? TestIds.BANNER}
                onAdFailedToLoad={(error: unknown) => {
                    console.warn('[AdMob] Banner failed to load:', error);
                    setIsFailed(true);
                }}
                onAdLoaded={() => setIsLoaded(true)}
            />
        </View>
    );
}

const styles = StyleSheet.create({
    container: { alignItems: 'center', marginVertical: 12, minHeight: 60, width: '100%' },
    placeholder: {
        alignItems: 'center', backgroundColor: '#1e1f20', borderColor: '#374151',
        borderRadius: 8, borderWidth: 1, justifyContent: 'center',
        paddingVertical: 18, width: '100%',
    },
    placeholderText: { color: '#6b7280', fontSize: 12, fontWeight: '600' },
});
```

### ⚠️ Anclar el banner, no meterlo en el scroll

El error más fácil es poner `<AdBanner />` adentro del `ScrollView`, al final del contenido.
Con poco contenido se ve; con mucho, queda al final del scroll y **el usuario no lo ve nunca**
— es decir, no monetiza. El banner va como hermano del `ScrollView`, al pie del contenedor:

```tsx
const [adHeight, setAdHeight] = useState(0);

<View style={styles.container}>            {/* flex: 1 */}
  <ScrollView contentContainerStyle={styles.content}>
    {/* ... contenido ... */}
  </ScrollView>

  {/* Fuera del ScrollView: si va adentro queda al final del contenido y no se
      ve hasta scrollear hasta abajo del todo. */}
  <View
    onLayout={event => setAdHeight(event.nativeEvent.layout.height)}
    style={styles.adBar}>
    <AdBanner />
  </View>

  {/* Un FAB tiene que subir lo que el banner ocupe. Se mide con onLayout en vez
      de hardcodear la altura, porque AdBanner devuelve null si falla y con un
      offset fijo el FAB quedaría flotando en el aire. */}
  <Pressable style={[styles.fab, { bottom: 24 + adHeight }]} />
</View>
```

Como el contenedor es `flex: 1` y el `ScrollView` toma el espacio restante, el banner queda
fijo abajo sin `position: absolute`. Si la pantalla vive dentro de un bottom tab navigator,
ese "abajo" ya es justo por encima de la barra de tabs.

Recordá subir el `paddingBottom` del `contentContainerStyle` para que la última tarjeta no
quede tapada por el FAB.

---

## 6. Intersticial

El hook `useInterstitialAd` devuelve `{ isLoaded, isOpened, isClicked, isClosed, error, isShowing, load, show }`.

### El patrón completo

```tsx
import { useCallback, useEffect, useRef } from 'react';
import { useInterstitialAd } from 'react-native-google-mobile-ads';
import { ADMOB_INTERSTITIAL_ID } from '../config/admob';

const interstitial = useInterstitialAd(ADMOB_INTERSTITIAL_ID);

// ⚠️ El hook devuelve un objeto NUEVO en cada render. Guardarlo en un ref permite
// usarlo desde callbacks sin recrearlos en cada render.
const interstitialRef = useRef(interstitial);
interstitialRef.current = interstitial;

const showInterstitialIfReady = useCallback(() => {
  if (!interstitialRef.current.isLoaded) {
    interstitialRef.current.load();
    return;   // se saltea y se precarga para la próxima
  }

  try {
    interstitialRef.current.show();
  } catch (error) {
    // show() lanza de forma síncrona si el anuncio dejó de estar listo. Nunca
    // debe propagarse: la acción del usuario ya se completó.
    console.warn('[AdMob] No se pudo mostrar el interstitial:', error);
  }
}, []);

// ⚠️ La instancia se crea dentro de un efecto del hook, así que en el primer
// render todavía es null y load() es un no-op. Dependiendo de la propia función
// `load` el efecto vuelve a correr en cuanto el anuncio existe, y ahí sí precarga.
// Depende SOLO de `load`, no del objeto entero: el hook devuelve un objeto nuevo
// en cada render, así que incluirlo relanzaría la carga en bucle infinito.
useEffect(() => {
  interstitial.load();
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [interstitial.load]);

// Recargar apenas se cierra, para que el próximo esté listo.
useEffect(() => {
  if (interstitial.isClosed) {
    interstitialRef.current.load();
  }
}, [interstitial.isClosed]);
```

### ⚠️ El anuncio nunca puede bloquear la acción del usuario

Si el intersticial precede a una navegación, el flujo tiene que funcionar aunque el anuncio
no cargue (**el no-fill es lo normal**, no la excepción) y aunque el evento de cierre nunca
llegue (por ejemplo si mandan la app a segundo plano):

```tsx
const CHAT_AD_TIMEOUT_MS = 5000;
const pendingActionRef = useRef<string | null>(null);
const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

// Idempotente: solo ejecuta la primera vez, venga del cierre del anuncio o del resguardo.
const consumePendingAction = useCallback(() => {
  if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
  const target = pendingActionRef.current;
  if (!target) return;
  pendingActionRef.current = null;
  navigateSomewhere(target);
}, []);

const goAfterAd = useCallback((target: string) => {
  if (!interstitialRef.current.isLoaded) {
    interstitialRef.current.load();
    navigateSomewhere(target);   // sin anuncio, se va directo
    return;
  }
  pendingActionRef.current = target;
  timeoutRef.current = setTimeout(consumePendingAction, CHAT_AD_TIMEOUT_MS);
  showInterstitialIfReady();
}, [consumePendingAction, showInterstitialIfReady]);

useEffect(() => {
  if (interstitial.isClosed) {
    consumePendingAction();
    interstitialRef.current.load();
  }
}, [interstitial.isClosed, consumePendingAction]);

// Y al desmontar, no dejar la acción colgada.
useEffect(() => () => consumePendingAction(), [consumePendingAction]);
```

⚠️ Sin el timeout de resguardo, en un dispositivo físico con no-fill la acción no se
ejecutaba nunca y la pantalla quedaba colgada. Nos pasó.

### ⚠️ iOS: no mostrar el intersticial mientras se cierra un Modal

Este es el bug más difícil de diagnosticar de todos, porque **falla en silencio**: el
intersticial simplemente no aparece, sin error ni log.

El SDK busca el view controller más alto para presentar el anuncio, pero descarta los que
están en pleno cierre. Si mostrás el intersticial justo después de cerrar un `<Modal>` de RN,
en iOS no se presenta nada.

La solución usa `onDismiss`, que es **exclusivo de iOS** y corre cuando el modal terminó de
cerrarse:

```tsx
async function handleSubmit() {
  await doTheThing();
  shouldShowAdRef.current = true;
  closeModal();

  // En Android no existe esa restricción y no hay onDismiss, así que se muestra acá.
  if (Platform.OS !== 'ios') {
    showInterstitialIfReady();
  }
}

<Modal
  onDismiss={showInterstitialIfReady}   // solo iOS
  onRequestClose={closeModal}
  visible={isVisible}>
```

Con un flag en un ref (`shouldShowAdRef`) porque `onDismiss` se dispara en **todo** cierre del
modal, también cuando el usuario cancela:

```tsx
const shouldShowAdRef = useRef(false);

const showInterstitialIfReady = useCallback(() => {
  if (!shouldShowAdRef.current) return;
  shouldShowAdRef.current = false;
  // ... el resto igual
}, []);
```

El mismo problema aplica al **share sheet nativo** (`Share.share`) y a cualquier cosa que
presente un view controller: si el modal que lo contiene se está cerrando, no aparece. En esos
casos lo más simple es no cerrar el modal.

### Dónde disparar el intersticial

Reglá de oro: en una **transición que el usuario ya espera** (después de crear algo, al pasar
de una pantalla a otra), nunca en medio de una tarea. Y nunca en el arranque de la app.

⚠️ Las políticas de AdMob prohíben intersticiales que interrumpan una acción en curso o que
aparezcan apenas se abre la app. Es causa de suspensión de cuenta, no solo de menos ingresos.

---

## 7. `app-ads.txt`

Archivo de texto en el **sitio web del desarrollador** (no en la app) que verifica que el
inventario lo vende su dueño real. Sin él, AdMob marca la app como no verificada y buena
parte de la demanda programática filtra el tráfico.

Contenido (una línea por red de anuncios):

```
google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0
```

| Campo | Qué es |
| --- | --- |
| `google.com` | El sistema que vende el inventario |
| `pub-XXXXXXXXXXXXXXXX` | Tu publisher ID: los 16 dígitos del App ID, **sin** el prefijo `ca-` |
| `DIRECT` | Sos el dueño directo de la cuenta |
| `f08c47fec0942fa0` | Certification authority ID de Google |

Va en el dominio que figura como **Sitio web** en la ficha de Play Console / App Store. Un solo
archivo cubre Android y iOS, porque comparten publisher ID.

⚠️ **En Laravel el document root no es la carpeta del proyecto, sino `public/`.** Un
`app-ads.txt` al nivel del proyecto nunca se sirve. Regla práctica: ponelo en la **misma
carpeta que `robots.txt`**, que por definición es el document root que ya funciona. Permisos `644`.

⚠️ El crawler de AdMob **descarta los prefijos `www.` y `m.`**, así que pide el dominio pelado.
Si el sitio solo responde con `www`, la verificación falla.

Verificación de las cuatro variantes que prueba el crawler:

```bash
DOMINIO=tudominio.com
for u in "http://$DOMINIO/app-ads.txt" "https://$DOMINIO/app-ads.txt" \
         "http://www.$DOMINIO/app-ads.txt" "https://www.$DOMINIO/app-ads.txt"; do
  printf '%s -> %s\n' "$u" "$(curl -sSL -o /dev/null -w '%{http_code} %{content_type}' "$u")"
done
```

Las cuatro tienen que dar `200 text/plain`. Si alguna devuelve `text/html`, el hosting está
sirviendo una página de error (algunos devuelven 200 + HTML en vez de 404) y AdMob no la parsea.

El crawl tarda **hasta 24 h**; mientras tanto el estado queda pendiente, y eso no es un fallo.

---

## 8. Checklist de implementación

**Setup**
- [ ] `npm install react-native-google-mobile-ads` + `pod install` + rebuild nativo
- [ ] Apps creadas en AdMob (una por plataforma) y ad units de banner e intersticial
- [ ] `src/config/admob.ts` con `Platform.select` y switch `__DEV__` **en banner e intersticial**
- [ ] App ID en `AndroidManifest.xml` con `tools:replace="android:value"`
- [ ] `GADApplicationIdentifier`, `NSUserTrackingUsageDescription` y `SKAdNetworkItems` en `Info.plist`
- [ ] Inicialización en `App.tsx` con timeout de consent y `finally` que desbloquea la app
- [ ] Mock de la librería en el setup de Jest si hay tests

**Banner**
- [ ] Se auto-oculta si falla (`return null`), sin dejar hueco
- [ ] Anclado fuera del `ScrollView`, no al final del contenido
- [ ] El FAB (si hay) sube según la altura medida con `onLayout`

**Intersticial**
- [ ] Instancia guardada en un `ref`
- [ ] Precarga con `useEffect` dependiendo **solo** de `interstitial.load`
- [ ] Recarga en `isClosed`
- [ ] `show()` envuelto en `try/catch`
- [ ] La acción del usuario se ejecuta igual si hay no-fill, con timeout de resguardo
- [ ] En iOS se presenta desde `onDismiss` del Modal, con flag en ref
- [ ] Se dispara en una transición esperada, nunca en el arranque ni en medio de una tarea

**Producción**
- [ ] `app-ads.txt` publicado y devolviendo `200 text/plain` en las cuatro variantes de URL
- [ ] Verificado que en release se usan los ad units reales y en debug los de test
- [ ] Nunca se tocaron los propios anuncios de producción durante el desarrollo
