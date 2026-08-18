# CamStream — cámara/pantalla del móvil en una URL (estilo VDO.Ninja casero)

App de Android que emite tu cámara (trasera o delantera) o la pantalla del móvil por
WebRTC, con un enlace `https://tu-servidor/watch/sala` que cualquiera puede abrir en un
navegador para verlo en directo, sin instalar nada. Incluye micrófono con botón de
silenciar y una función experimental de audio interno (ver limitaciones más abajo).

El vídeo/audio viaja siempre **directo entre el móvil y quien lo ve** (peer-to-peer, como
hace VDO.Ninja). El servidor que tienes que desplegar solo hace de "intermediario de
presentación" al principio (intercambiar unos mensajes técnicos para que ambos lados se
encuentren) — no ve ni graba el vídeo.

## Qué hay en esta carpeta

```
server/          servidor de señalización (Node.js) + página web del visor
android-app/     proyecto de Android Studio (Kotlin) con la app
```

## 1. Desplegar el servidor (una sola vez, gratis)

Necesitas que el servidor esté accesible por internet con una URL `https://...` para que
funcione fuera de tu propia WiFi (y para que los navegadores acepten el WebRTC).

**Opción recomendada: Render.com (gratis)**

1. Crea una cuenta en https://render.com
2. Sube la carpeta `server/` a un repositorio de GitHub (o usa "Deploy from a public Git
   repo" si ya tienes uno).
3. En Render: **New + → Web Service**, conecta el repo, y configura:
   - Root directory: `server`
   - Build command: `npm install`
   - Start command: `npm start`
   - Plan: Free
4. Cuando termine el despliegue, Render te da una URL como
   `https://camstream-xxxx.onrender.com`. Esa es la que pones en el campo "URL del
   servidor" de la app.

**Alternativas**: Railway, Fly.io, un VPS propio con Node.js instalado (`node server.js`,
detrás de Nginx/Caddy con HTTPS), o incluso tu propio ordenador con `npm start` si solo
vas a usarlo en tu red local (usa `http://192.168.x.x:8080` en ese caso, sin HTTPS).

**Probarlo en local antes de desplegar** (opcional):
```bash
cd server
npm install
npm start
# abre http://localhost:8080 en el navegador para comprobar que arranca
```

## 2. Compilar la app (Android Studio)

No se incluye un `.apk` ya compilado porque generarlo requiere descargar la librería de
WebRTC y las dependencias de Android desde los repositorios de Google/Maven, a los que
este entorno de trabajo no tiene acceso. Compilarlo tú en Android Studio es rápido:

1. Instala [Android Studio](https://developer.android.com/studio) si no lo tienes.
2. **File → Open** y selecciona la carpeta `android-app/`.
3. Espera a que termine el "Gradle Sync" (la primera vez descarga la librería WebRTC,
   pesa bastante, puede tardar unos minutos).
4. Conecta tu móvil por USB (con la depuración USB activada) o usa un emulador.
5. Pulsa el botón ▶ Run, o **Build → Build Bundle(s) / APK(s) → Build APK(s)** para
   generar el `.apk` instalable (aparece en
   `android-app/app/build/outputs/apk/debug/app-debug.apk`).
6. Para instalarlo en otro móvil sin cable: comparte ese `.apk` (por ejemplo por Drive o
   WhatsApp) y ábrelo en el móvil habiendo permitido "instalar apps de orígenes
   desconocidos".

### Si Android Studio se queja de la dependencia de WebRTC

El fichero `android-app/app/build.gradle.kts` usa:
```kotlin
implementation("io.github.webrtc-sdk:android:125.6422.07")
```
Si esa versión concreta ya no existe cuando lo compiles, Android Studio te lo señalará en
rojo. Solución: abre https://mvnrepository.com/artifact/io.github.webrtc-sdk/android,
copia la versión más reciente que aparezca, y sustitúyela en esa línea.

### Si aparece un error de "método no implementado" en `WebRtcClient.kt`

Las interfaces internas de la librería WebRTC (como `PeerConnection.Observer`) a veces
añaden algún método nuevo entre versiones. Si Android Studio marca un error de "clase
abstracta / método no implementado" en `createPeerConnectionForViewer`, usa el atajo
Alt+Enter → "Implement members" sobre la clase anónima y déjalo con el cuerpo vacío
(`{}`); no afecta al funcionamiento.

## 3. Usar la app

1. Abre la app, pon la URL de tu servidor desplegado (paso 1) y un nombre de sala (o deja
   el que se genera solo).
2. Elige **Cámara** o **Pantalla**.
3. Concede los permisos que te pida (cámara/micrófono, y en el caso de pantalla, la
   confirmación del sistema para grabarla).
4. Pulsa **Iniciar transmisión**. En unos segundos aparece el enlace y el código QR.
5. Comparte ese enlace (o el QR) con quien quieras que lo vea — lo abren en cualquier
   navegador (Chrome, Safari, Firefox...) y ya está, sin instalar nada.
6. Puedes silenciar el micrófono, cambiar de cámara (frontal/trasera) o parar la
   transmisión en cualquier momento desde la app o desde la notificación.

Una sala = un móvil emitiendo. Pueden verla varias personas a la vez.

## Limitaciones conocidas

- **Audio interno (experimental) no se mezcla todavía en directo.** La librería de
  WebRTC para Android no permite, sin tocar su código nativo, sustituir su fuente de
  audio por una grabación externa. Por eso, cuando activas "Audio interno" (solo
  disponible compartiendo pantalla, Android 10+), la app graba el audio interno del
  teléfono en paralelo a un archivo `.wav` en el almacenamiento de la app
  (`Android/data/com.miconstelacion.camstream/files/audio_interno/`), pero **no** se
  envía mezclado con el micrófono en la emisión en directo. Es una base de código real y
  funcional para seguir desarrollando la mezcla en directo más adelante (requeriría
  código nativo/C++ o forkear partes internas de la librería WebRTC — no era seguro
  improvisarlo sin poder probarlo antes de entregártelo).
- **Sin servidor TURN.** Con redes normales (WiFi doméstica, datos móviles) funciona con
  los servidores STUN públicos incluidos. En redes muy restrictivas (WiFi corporativas,
  algunas 4G/5G con NAT simétrico) puede que la conexión P2P no consiga establecerse. Si
  te pasa, la solución es añadir un servidor TURN (por ejemplo, el gratuito de
  https://www.metered.ca/tools/openrelay/) en la lista `iceServers` de
  `WebRtcClient.kt` y de `watch.html`.
- **Una emisión por sala.** Si dos móviles intentan usar la misma sala a la vez, el
  segundo recibe un error. Usa nombres de sala distintos si vais a emitir varias
  personas.
- **La contraseña de sala es una protección básica**, no cifrado — evita que cualquiera
  que adivine el nombre de la sala pueda verla, pero viaja en la URL. No la uses para
  contenido realmente sensible.
- No se ha podido compilar/ejecutar el proyecto en este entorno de trabajo (sin acceso a
  los repositorios de Android), así que, aunque el código se ha escrito con mucho
  cuidado siguiendo las APIs estándar de Android/WebRTC, es posible que necesites algún
  ajuste menor al compilarlo por primera vez en Android Studio.
