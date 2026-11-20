# AceTV para Fire TV - estado del proyecto

Versión: **0.7.1** (fusión de canales con más reglas, Menú = fuentes / borrar reciente; fuentes con reintento y cambio manual; servidor del móvil con QR; listas integradas, vigilante, estadísticas)

## Objetivo
App nativa Android (Kotlin + Media3/ExoPlayer) para Fire TV equivalente a la app webOS ya terminada (v1.9.0):
reproductor de canales de un motor Acestream en la LAN, con lista de canales, "Recientes", varias fuentes por canal
y control desde el móvil por QR (servidor HTTP dentro de la app, puerto 8765).

## Dispositivo de destino
- Fire TV Stick 2.ª gen (AFTT), Fire OS 5.2.9.5 = Android 5.1.1 (API 22), armeabi-v7a, ~895 MB RAM.
- IP 192.168.1.127, ADB por red puerto 5555 (autorizar clave RSA con "Permitir siempre"). Red 192.168.1.x.
- Fire OS puede mostrar la pantalla de selección de perfil al lanzar por ADB y quitar el foco: dejar el Fire TV en el
  inicio con el perfil elegido antes de probar.

## Motor Acestream
- 192.168.1.34:6878, versión 3.1.49 (linux); no es el 3.2.11 de las TVs LG.
- TS (FUNCIONA, modo por defecto): GET /ace/getstream?id=HASH&format=json -> {response:{playback_url,stat_url,command_url}};
  playback_url responde 302 a /content/...; ExoPlayer lo sigue; mime forzado video/mp2t.
- HLS: GET /ace/manifest.m3u8?id=HASH&format=json; con este motor la lista .m3u8 no llega (40-60 s sin respuesta).
- Cerrar siempre la sesión: GET command_url?method=stop (al salir, cambiar de canal o si una respuesta llega tarde).
- Canal de pruebas: d65257bb934b73647374224fd62d836815804be2 (no está en lista.json). Hashes sin contenido real devuelven
  un infohash repetido y la lista nunca llega.
- Listas: lista.json y lista.m3u de la carpeta del proyecto se copian a los assets al compilar (tarea copiarListas) y se
  cargan juntas al arrancar (un hash una sola vez, gana la primera aparición; el JSON va primero). Para cambiarlas:
  sustituir los ficheros y recompilar. lista.json de pruebas: 153 canales, 17 grupos.

## Entorno de desarrollo (Ubuntu pedro@ryzen7-5700u, 192.168.1.159)
- JDK 21, Android SDK en ~/Android/Sdk (android-35, build-tools 35.0.1), adb de apt, Gradle 8.9 propio en
  ~/tools/gradle-8.9 (wrapper en el proyecto).
- Proyecto ~/acetv-firetv (paquete local.acetv): AGP 8.7.3, Kotlin 2.0.21, minSdk 22, targetSdk 28, Media3 1.5.1,
  android.useAndroidX=true, JUnit 4.13.2 para tests.
- Copias del instalable y del ESTADO: ~/acetv-firetv-releases/. Sondeo de canales: ./probar.sh [GRUPO] [N_OK].

## Estructura
- app/src/main/java/local/acetv/MainActivity.kt: UI (grupos | canales), mando, sesión con el motor y ExoPlayer.
- app/src/main/java/local/acetv/Channels.kt: lógica pura (Channel, ChannelLogic: dedupe, grupos, recientes, zapping).
- app/src/main/java/local/acetv/MiniJson.kt: lector JSON mínimo sin dependencias (probado contra Python con lista.json).
- app/src/main/java/local/acetv/ListParsers.kt: lectura de JSON y M3U (hash de acestream:// o ?id=, grupo, logo, tvg-id,
  "Titulo --> ORIGEN" separa el origen), mergeUnique.
- app/src/main/java/local/acetv/HttpServer.kt: MiniHttpServer (ServerSocket, 4 hilos, Content-Length, 8 MB, 413/400).
- app/src/main/java/local/acetv/RemoteSession.kt: código de 8 hex, caduca a los 5 min sin actividad, bloqueo a los 30 erróneos.
- app/src/main/java/local/acetv/RemoteApi.kt: rutas /s/<código>/... (página y API JSON) y la interfaz RemoteController.
- app/src/main/java/local/acetv/RemoteSupport.kt: JsonOut, Search (sin tildes, todas las palabras) y EngineCheck.
- app/src/main/java/local/acetv/QrRender.kt: matriz de módulos y píxeles del QR (sobre la biblioteca de Project Nayuki).
- app/src/main/java/io/nayuki/qrcodegen/*.java: QR Code generator library (Java), Project Nayuki, licencia MIT, copiada de
  github.com/nayuki/QR-Code-generator (rama master) y verificada con sha256 (BitBuffer d5496452..., DataTooLongException 7661186d..., QrCode 01715bae..., QrSegment 70f10e51...); no editar.
- app/src/main/assets/remote.html: página del móvil (buscador-mando, ID manual, motor, subida de listas).
- app/src/main/java/local/acetv/ChannelMerge.kt: fusión de entradas en filas (Row) con fuentes (Source) ordenadas.
- app/src/main/java/local/acetv/SourcePlan.kt: orden de intentos, reintentos y cambio manual de fuente.
- app/src/main/java/local/acetv/StallDetector.kt: lógica pura del vigilante (posición congelada durante N ms).
- app/src/test/java/local/acetv/ChannelLogicTest.kt: tests unitarios (./gradlew testDebugUnitTest).

## Compilar, instalar y probar
    cd ~/acetv-firetv
    ./gradlew testDebugUnitTest 2>&1 | tail -8
    ./gradlew assembleDebug 2>&1 | tail -6
    adb connect 192.168.1.127:5555
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -S -n local.acetv/.MainActivity
    adb logcat -v time -d | grep -E "AceTV|FATAL|AndroidRuntime" | tail -25
    adb shell dumpsys meminfo local.acetv | grep -E "Native Heap|Dalvik Heap|TOTAL"

Extras del intent: hash (reproducir un hash suelto, depuración), host y mode (hls|ts), los dos últimos se guardan.
Reset de datos de la app: adb shell pm clear local.acetv. Al pegar comandos, sustituir HASH entero (sin < >).

## Comportamiento (v0.3.x)
- Lista: grupos a la izquierda (primero "★ Recientes") y canales a la derecha; OK reproduce.
- Reproduciendo: Arriba/Abajo (o CH+/CH-) zapping circular dentro del grupo; OK muestra el nombre 3 s; Back vuelve a la
  lista; Back en la lista sale.
- Guarda en SharedPreferences "acetv": last (último canal; al abrir vuelve a él), recents (8 hashes), host, mode.
- onStop libera el reproductor y cierra la sesión; onStart la reabre si se estaba reproduciendo.
- Memoria: app vacía ~12,7 MB PSS; reproduciendo TS ~26-28 MB, estable tras 10 min.

## Notas de pruebas (v0.3.x)
- Zapping con espera: el nombre del canal se muestra al pulsar, pero la sesión y el reproductor solo se abren cuando
  pasan 400 ms sin otra pulsación (evita ráfagas de sesiones y de decodificadores). Mantener pulsada la tecla repite.
- Causa del ERROR_CODE_FAILED_RUNTIME_CHECK: IllegalStateException "Playback stuck buffering and not loading" (vigilante
  de ExoPlayer). Hipótesis A: el tope de 8 MB del búfer paraba la carga antes de reunir tiempo suficiente; B: el motor
  corta el flujo en un canal caído. v0.3.2: búfer 10-20 s con prioridad al tiempo sobre el tamaño, log del canal, del
  búfer y del stat_url al fallar, y un reintento automático a 1 s (se reinicia al reproducir).
- Una vez salió ERROR_CODE_FAILED_RUNTIME_CHECK tras unos 10 cambios de canal en 1 s; no se ha vuelto a ver. El log
  guarda ya la excepción completa (etiqueta AceTV, "error de reproduccion").
- Decodificador: MediaTek MT8127, H.264 por hardware (HW.video.avc), canales a ~720p. HEVC probablemente no funciona
  (sin comprobar).
- Memoria con la lista y zapeo intenso: ~35 MB PSS (Dalvik ~15 MB).

## Vigilante y estadísticas (v0.4.0)
- Cada segundo: si el reproductor está en READY (o en BUFFERING tras haber arrancado) y la posición no avanza 15 s, o si
  no arranca en 45 s, se llama a onStalled: un reintento automático (abre sesión nueva) y, si falla otra vez, mensaje
  "Sin senal". retries se pone a cero al reproducir (READY) y al elegir canal.
- OK en reproducción muestra u oculta el panel de estadísticas (arriba a la derecha): estado, pares, KB/s de bajada y
  subida (stat_url, cada 2 s), resolución y segundos de búfer.
- Tecla Menú: provisional, simula una parada para probar el vigilante (más adelante se le dará otra función).
- Prueba: una pulsación de Menú reabre la sesión y el canal vuelve a reproducir en ~2,3 s. Dos pulsaciones seguidas
  gastan el único reintento y dejan "Sin senal" (esperado). Con fuentes: 1 reintento por fuente y 3 en la última.
- Sin comprobar todavía: varios minutos sin tocar nada para descartar falsas alarmas del vigilante
  (adb logcat -v time -d | grep -E "vigilante|Sin senal").

## Servidor del móvil y QR (v0.6.1)
- Servidor HTTP propio (java.net.ServerSocket, sin dependencias, probado en JVM) en el puerto 8765, cuerpos hasta 8 MB.
- Sesión: código aleatorio de 8 hex, URL http://IP_TV:8765/s/<código>. Caduca a los 5 min sin actividad; a las 30
  peticiones con código erróneo se bloquea hasta generar un QR nuevo en la TV. La IP se obtiene con NetworkInterface.
- TV: nueva entrada "+ Nuevo QR / ajustes" en la columna de grupos; a la derecha QR (biblioteca de Project Nayuki), URL, IP y modo
  del motor, lista activa (incluida o subida). OK en ese panel genera un código nuevo.
- API JSON bajo /s/<código>/: GET / (página), api/state, api/search?q= (sin mayúsculas ni tildes, todas las palabras),
  POST api/play {hash}, api/zap {delta}, api/engine {host} (valida con get_version "version" o getstream?format=json
  con "response" y "error"; si no hay motor no cambia nada y devuelve el motivo), api/list/preview (cuerpo = fichero,
  cabecera X-Filename; resumen de canales, grupos y repetidos), api/list/apply {action: replace|reset}.
- Página del móvil (una sola, assets/remote.html): buscador que actúa de mando (tocar un canal lo reproduce y se puede
  seguir cambiando), botones de zapping, ID manual de 40 hex, cambio de IP del motor, subida de .json/.m3u con vista
  previa y "Sustituir" / "Volver a la lista incluida".
- Lista subida: filesDir/custom_list.json (JSON normalizado); si existe sustituye a la incluida; "Volver" la borra.
- Estado v0.6.0: servidor, API, página y lista subida implementados y probados (tests con HTTP real y la página en un
  navegador simulado). En la app se arranca con: adb shell am start -S -n local.acetv/.MainActivity --ez mando true;
  la URL con el código sale en el log (etiqueta AceTV, línea "mando:"). La lista subida se guarda en
  filesDir/custom_list.json y sustituye a la incluida hasta pulsar "Volver a la incluida".

## Mando y foco (v0.6.2)
- Fallo visto en v0.6.1: tras elegir un canal desde el móvil, OK en el mando lanzaba el canal marcado en la lista en
  lugar de mostrar las estadísticas (un ListView oculto conservaba el foco). Corrección: dispatchKeyEvent intercepta
  Back, ▲/▼, CH+/CH-, OK y Menú cuando se reproduce y la lista está oculta. El log registra cada tecla
  ("tecla ... playing= lista= foco=") y cada canal que arranca ("canal: ...").
- El canal MOVISTAR PLUS 720p * se corta cada ~55 s (1 par, 490-828 KB/s); el vigilante lo reconecta en 2-6 s. Es una
  fuente justa, no un fallo de la app; la fusión de fuentes permitirá cambiar sola a otra.
- Tests: 52 (ChannelLogic 8, ListJson 1, ListParsers 6, MiniHttpServer 6, QrAndPanel 5, RemoteApi 11, RemoteSession 5,
  SearchAndEngine 5, StallDetector 5).

## Fusión de canales y fuentes (v0.7.0)
- ChannelMerge: clave = país [XX] + nombre sin calidad (HD/FHD/4K/720p/1080p...), sin "Spain", sin notas entre
  paréntesis ni asteriscos; M+/Movistar+/Movistar Plus se unifican y La Liga = LaLiga; compara sin tildes ni espacios
  (el signo + cuenta). Una fila por canal, en el grupo donde apareció primero; fuentes ordenadas por resolución
  (estable); etiqueta de fuente = calidad + asteriscos (+ origen). lista.json de prueba: 153 entradas -> 62 canales.
- SourcePlan: empieza por la fuente que funcionó la última vez (preferencia "pref:<clave>" en las preferencias);
  después las demás por resolución; las de más de 1080p (maxResolution del Fire TV Stick 2.ª gen) se dejan para el
  final. Un reintento por fuente y tres en la última; arranque con 25 s si quedan fuentes y 45 s si no.
- MainActivity (reescrita en v0.7.0): la lista muestra filas con "(n)" fuentes; recientes y último canal se guardan por
  hash de la fuente (cualquier hash apunta a su fila); ◄/► cambian de fuente a mano; el título muestra [i/n etiqueta].
- Servidor: stop() espera al hilo de aceptación (una prueba fallaba al azar). Tests: 66.
- Reglas que no cubre: p. ej. "MOVISTAR LALIGA" (sin "plus") no se une a "M+ LaLiga"; añadir sinónimos en
  ChannelMerge.key si hace falta tras ver el resultado con lista.m3u.

## Distribución — Descarga directa desde GitHub Releases

- **Repositorio:** https://github.com/drollo77/acetv-firetv-oc
- **Release v0.7.0:** https://github.com/drollo77/acetv-firetv-oc/releases/tag/v0.7.0
- **Enlace directo al APK:** https://github.com/drollo77/acetv-firetv-oc/releases/download/v0.7.0/app-debug.apk
- **Tamaño APK:** 3.9 MB (4,059,013 bytes)
- **Instalación en Fire TV:**
  - **Downloader** (app de Amazon): pegar enlace directo → instala automáticamente
  - **ADB:** `adb install app/build/outputs/apk/debug/app-debug.apk`
- **Token PAT GitHub:** configurado en máquina local para push y API (expira 2027-02-xx)
- **Flujo de release:** commit → tag `vX.Y.Z` → `git push origin main vX.Y.Z` → crear release en GitHub web → subir APK como asset vía API

## Teclas y reglas añadidas (v0.7.1)

- Reproduciendo: ▲/▼ y CH+/CH- zapping dentro del grupo (400 ms de espera); ◄/► cambian de fuente; OK estadísticas;
  Menú lista las fuentes del canal (6 s, marca la actual); Back vuelve a la lista. En la lista: OK reproduce; en
  "★ Recientes" Menú borra el canal marcado; en "+ Nuevo QR / ajustes" OK genera un código nuevo.
- Reglas de fusión añadidas tras ver lista.m3u (títulos tipo "M. Deportes 2 HD --> New Loop ...446"): "M." = "M+",
  "Movistar X" = "M+ X" (juicio nuestro: revisar si une canales distintos), "LA 1/LA 2" = "La 1 TVE/La 2 TVE".
- lista.m3u: grupos "Internacional" (192), "New Loop" (72), "New Era" (30): son nombres de origen, no de contenido;
  los canales solo del M3U caen en esos grupos (grupo de la primera aparición). Posible mejora: reasignar grupos.
- Tests: 67 (1 conocido fallido: `listaRealDelProyecto` — bug en el origen, las listas v0.7.1 no tienen los datos
  que el test espera para "M+ Deportes").

## Distribución — Descarga directa desde GitHub Releases

- **Repositorio:** https://github.com/drollo77/acetv-firetv-oc
- **Release v0.7.0:** https://github.com/drollo77/acetv-firetv-oc/releases/tag/v0.7.0
- **Enlace directo al APK:** https://github.com/drollo77/acetv-firetv-oc/releases/download/v0.7.0/app-debug.apk
- **Tamaño APK:** 3.9 MB (4,059,013 bytes)
- **Instalación en Fire TV:**
  - **Downloader** (app de Amazon): pegar enlace directo → instala automáticamente
  - **ADB:** `adb install app/build/outputs/apk/debug/app-debug.apk`
- **Token PAT GitHub:** configurado en máquina local para push y API (expira 2027-02-xx)
- **Flujo de release:** commit → tag `vX.Y.Z` → `git push origin main vX.Y.Z` → crear release en GitHub web → subir APK como asset vía API

## Pendiente / hoja de ruta
1. (Hecho en v0.7.0: fusión de canales por nombre y país, con las reglas de la app webOS.)
2. (Hecho en v0.4.0: vigilante 15 s, timeout 45 s, panel de estadísticas.) Varias fuentes: arrancar con la que funcionó, cambio manual, 1 reintento y salto a la siguiente (3 en la última),
   vigilante de 15 s sin avanzar, timeouts 45 s (25 s si quedan fuentes), panel de estadísticas con stat_url.
3. Servidor HTTP embebido (NanoHTTPD, puerto 8765) con QR: sesión /s/<8 hex>, caduca a los 5 min, bloqueo a 30 intentos;
   page.html del móvil (buscador sin tildes, mando, ID manual, cambio de IP del motor validado, subida de .json/.m3u).
4. Logos con caché pequeña (LruCache), pantalla de ajustes (IP del motor, modo TS/HLS), búsqueda.
- Teclas propuestas: Izquierda/Derecha cambiar de fuente, OK estadísticas, Menú borrar reciente.
- Limitaciones: API 22 (sin java.time ni APIs posteriores), RAM justa, Android 5 sin Ktor.

## Cómo trabajamos
- En español; Pedro ejecuta los comandos en el Ubuntu y pega la salida. Pasos pequeños verificables, ficheros completos
  con cat > ... <<'EOF', pruebas automáticas antes de entregar código, un commit y una etiqueta por versión, copia del
  instalable por versión y ESTADO.md actualizado en cada versión guardada.