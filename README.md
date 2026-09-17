# Longcasting

App offline para medir y registrar lanzamientos de longcasting. Kotlin Multiplatform + Compose
Multiplatform, Android e iOS, sin servidor: todo se guarda en SQLite en el teléfono.

Marcás el vértice con un botón **una sola vez por jornada**, caminás hacia la plomada viendo en
vivo la distancia en línea recta y la distancia recorrida, y al llegar fijás el punto de caída.
El tiro queda guardado contra un tirador y una plomada.

El origen queda guardado en la base, así que los tiros siguientes no vuelven a pedir la
ocupación de 60 s. La pantalla muestra siempre a cuántos metros del origen estás parado y avisa
si te alejaste: es la red de contención contra el peor error posible, que es llegar a otra
cancha, olvidarse de marcar el vértice y medir toda la tarde contra el de ayer. El origen
caduca solo al cambiar el día, y hay un botón "Cambiar origen" para rehacerlo cuando quieras.

El historial se filtra por fecha (Hoy, últimos 7 días, o cualquier día que tenga tiros), por
plomada y por tirador, y tiene dos vistas: **Tiros**, la lista cronológica, y **Ranking**, quién
hizo la mejor marca ordenado de mayor a menor. El ranking se separa siempre por plomada, porque
comparar un tiro de 175 g contra uno de 100 g no significa nada, e ignora los tiros
invalidados.

## Qué precisión esperar

**El GPS de un teléfono no mide centímetros.** Un fix típico tiene 3–8 m de error absoluto;
con doble frecuencia (L1+L5) baja a 1–3 m.

Lo que salva la medición es que acá no hace falta la posición absoluta sino el **vector entre
dos puntos** tomados con el mismo receptor con pocos minutos de diferencia y a 200–300 m. En
esa resta se cancelan casi por completo los errores dominantes (efemérides, reloj satelital,
ionosfera, troposfera), porque son prácticamente idénticos en ambos extremos.

Lo que no se cancela — ruido térmico, multipath, moverse durante la toma — se ataca con
ocupación estática promediada en ambos extremos, rechazo robusto de outliers y ponderación por
precisión declarada.

**Resultado esperado: ±0,3 a ±1,5 m en un tiro de 200 m.** Sirve para entrenar, comparar
plomadas y seguir progreso. **No reemplaza la cinta** (±1 cm). Para una marca oficial, medí con
cinta y cargala en el tiro: pasa a ser la medida oficial y la del GPS queda al lado para
comparar.

Sobre "conectarse a satélite": el teléfono **ya** habla con los satélites, y eso funciona sin
señal de celular — el GNSS es inherentemente offline. Ningún teléfono de consumo permite
recibir correcciones RTK por satélite ni usar el SOS satelital como enlace de datos para una
app. La única vía real a centímetros sería un receptor externo RTK con base propia en el
vértice, que hoy está fuera de alcance pero que la arquitectura no bloquea.

### La incertidumbre que muestra la app

El `±` que ves es de **repetibilidad**: cuánto se movió la solución durante la toma. No captura
el sesgo por multipath, que promediar no elimina. La exactitud real se mide en cancha con la
prueba de cierre y la prueba de la cinta (más abajo).

Un detalle que define todo el resto: los epochs GNSS están fuertemente autocorrelacionados, así
que el error estándar se calcula con un **tamaño de muestra efectivo** (`duración / 30 s`) y no
con la cantidad de muestras. Sin eso, promediar 120 epochs parecería reducir el error por raíz
de 120 y la app reportaría una precisión de centímetros que no existe.

### Cuánto hay que esperar

La ocupación **corta sola apenas la incertidumbre baja de la precisión buscada** (Ajustes, por
defecto ±1,50 m por punto); si no llega, agota el tiempo máximo. No hay atajo: cortar antes
siempre cuesta precisión, y por eso el corte depende de haberla conseguido, no del reloj.

Sigma por punto según el ruido del equipo y la duración, medido con series sintéticas:

| Precisión del fix | 20 s | 30 s | 45 s | 60 s | 90 s | 120 s |
|-------------------|------|------|------|------|------|-------|
| 1,5 m | 1,40 | 1,47 | 1,22 | 1,05 | 0,89 | 0,76 |
| 2,0 m | 1,87 | 1,97 | 1,62 | 1,40 | 1,18 | 1,02 |
| 3,0 m | 2,80 | 2,95 | 2,44 | 2,11 | 1,78 | 1,53 |
| 5,0 m | 4,67 | 4,93 | 4,07 | 3,52 | 2,97 | 2,55 |

Dos cosas que se leen ahí y conviene tener presentes:

- **Entre 15 y 30 s no se gana nada.** Por debajo de los 30 s el tamaño de muestra efectivo
  vale 1 y la sigma es directamente la dispersión cruda. La mejora recién arranca pasados los
  30 s. Cortar a los 25 s es lo peor de los dos mundos.
- **Con un fix de 3 m o peor, ±1,50 m no se alcanza en 60 s.** Ahí la toma va a agotar siempre
  el máximo. Si te pasa seguido, o subís la precisión buscada (aceptando un número más flojo) o
  subís el tiempo máximo a 120 s. La app te dice al cerrar cada toma cuál de las dos cosas pasó.

### Modo rápido

En Ajustes hay un **modo rápido**: un solo fix por punto, sin promediar. Instantáneo, con unos
±7 m de error en el tiro — es lo que hace cualquier app de medición por GPS, que te muestra ese
número igual con dos decimales.

Sirve para un valor aproximado, no para una marca. Por eso:

- Los tiros rápidos quedan **marcados en la base** (`throw_record.quickMode`), no deducidos de
  la sigma.
- El historial los muestra con una etiqueta "rápido", y el ranking avisa cuando la mejor marca
  de alguien se tomó así. Sin eso, una medición floja de 240 m encabezaría el podio sobre una
  buena de 225 m y nadie se enteraría.
- Como el modo se persiste, la pantalla de medir muestra un aviso mientras está activo y los
  botones dicen "RÁPIDO". Un modo impreciso que se puede olvidar prendido es una trampa.

## Estructura

```
shared/src/commonMain/kotlin/ar/com/longcasting/
  core/geo/        WGS84, ECEF, plano tangente local (ENU), distancias y rumbos
  core/gnss/       Fix, calidad de señal, interfaz LocationEngine
  core/measure/    StaticPointEstimator, PathLengthAccumulator, LiveTracker, redondeo oficial
  data/            SQLDelight, repositorios, ajustes, exportación CSV
  feature/         measure · history · rosters · settings
  ui/              tema y componentes de cancha
shared/src/androidMain/   LocationManager + GnssStatus, export, vibración
shared/src/iosMain/       CoreLocation, export, feedback háptico
```

El acceso a sensores es lo único específico de plataforma: `LocationEngine` es una interfaz
común, no un `expect/actual`, así que los tests reproducen trazas sin tocar código nativo.

**Android expone GNSS crudo** (satélites, CN0, portadora, detección de L5); iOS solo entrega
posición ya procesada, así que ahí el techo de precisión es más bajo y esos campos van nulos.

## Correr

```bash
./gradlew :androidApp:assembleDebug     # APK de Android
./gradlew :shared:testAndroidHostTest   # tests de la lógica y de los repositorios
open iosApp/iosApp.xcodeproj            # iOS
```

Los tests usan series sintéticas con semilla fija y SQLite real en memoria — sin mocks.

## Verificación en cancha

Ningún test unitario valida la exactitud real. Estas cuatro pruebas sí, y conviene hacerlas
una vez con cada teléfono que se vaya a usar:

1. **Diagnóstico.** Ajustes → Diagnóstico GNSS, a cielo abierto. Confirmar ≥8 satélites, CN0
   medio >35 dB-Hz, y si el equipo reporta L5.
2. **Prueba de la cinta.** Tender 100 m de cinta, medir con la app entre los dos extremos y
   comparar. Repetir 5 veces. La diferencia media y su dispersión son la exactitud real del
   equipo. **Anotala acá abajo.**
3. **Prueba de cierre.** Origen en el vértice, caminar 200 m, volver y usar "Verificar cierre".
   Debería quedar por debajo de 1,5 m.
4. **Prueba de reposo.** Fijar origen y quedarse quieto 3 minutos: la distancia recorrida tiene
   que mantenerse en 0,0 m.

### Resultados medidos

| Equipo | L5 | Prueba de cinta (100 m) | Error de cierre (200 m) |
|--------|----|-------------------------|-------------------------|
| _(completar)_ | | | |

## Fuera de alcance por ahora

- Chequeo de hilo con micrómetro (el diámetro mínimo por plomada ya se guarda: 100 g → 0,25 mm,
  125 g → 0,28, 150 g → 0,31, 175 g → 0,35).
- Calibración del vértice y el eje de la cancha, con validación del sector de 30°.
- Compuerta de movimiento por acelerómetro durante la ocupación.
- TDCP (fase de portadora diferenciada en el tiempo) como segundo estimador en Android.
- Receptor RTK externo por Bluetooth.
