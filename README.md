# CamStream — cámara/pantalla del móvil en una URL (estilo VDO.Ninja casero)

App de Android que emite tu cámara (trasera o delantera) o la pantalla del móvil por
WebRTC, con un enlace `https://tu-servidor/watch/sala` que cualquiera puede abrir en un
navegador para verlo en directo, sin instalar nada. Incluye micrófono con botón de
silenciar.

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
2. En la tarjeta **🎛️ Fuente y audio**, toca el botón grande de la fuente con la que
   quieres empezar: **🤳 Frontal**, **📷 Trasera** o **🖥️ Pantalla** (amarillo = activa,
   morado = apagada). El botón **🎙️ Micrófono** funciona igual: amarillo = encendido. El
   vídeo es opcional igual que el audio: si tocas la fuente que ya está activa, se apaga
   — puedes dejar los tres botones de fuente apagados a la vez (solo se emite audio) o
   el micrófono apagado (solo se emite vídeo), lo que necesites en cada momento.
3. Concede los permisos que te pida (cámara y micrófono se piden siempre, aunque
   empieces compartiendo pantalla, para poder cambiar de fuente más adelante sin
   cortar la emisión; para pantalla, además, la confirmación del sistema para
   grabarla — en Android 13+ ese mismo aviso del sistema ya te deja elegir entre
   grabar la pantalla completa o solo una app/ventana concreta).
4. Pulsa **Iniciar transmisión**. En unos segundos aparece el enlace y el código QR.
5. Comparte ese enlace (o el QR) con quien quieras que lo vea — lo abren en cualquier
   navegador (Chrome, Safari, Firefox...) y ya está, sin instalar nada.
6. **Con la transmisión ya en marcha puedes seguir tocando esos mismos botones para
   cambiar de fuente o de micrófono EN CALIENTE, sin parar ni reiniciar nada:** pasar
   de cámara trasera a pantalla (o al revés), encender/apagar el micrófono, o apagar
   el vídeo del todo (dejando solo audio), se nota en el enlace del espectador en
   cuestión de un segundo. Solo puede haber una cámara/pantalla activa a la vez (nunca
   las dos cámaras ni cámara+pantalla juntas) — por eso los tres botones de fuente se
   comportan como un selector, pero con la posibilidad de dejarlos los tres apagados: al
   marcar uno se desmarca el anterior, y tocar el que ya está activo lo apaga sin
   marcar ningún otro. Cambiar A pantalla (tanto al empezar como más tarde) siempre
   vuelve a pedir la confirmación del sistema para grabarla: es una limitación de
   Android, no se puede reutilizar un permiso de captura de pantalla anterior. Cada
   espectador se entera al instante de si hay vídeo y/o audio saliendo — con dos
   iconos sobre el vídeo (🎥/🎙️ tachados) que aparecen y desaparecen solos en cuanto
   apagas o enciendes la cámara/pantalla o el micrófono, tanto si ya estaban viendo
   como si se conectan después.
7. También puedes parar la transmisión en cualquier momento desde la app o desde la
   notificación persistente.

### Calidad de imagen

La cámara captura ahora a la resolución MÁXIMA que ofrezca el sensor, hasta 4K (2160p) —
ya no se limita artificialmente a 1080p. El techo de bitrate sube a la vez con la
resolución real que se esté usando (2,5 Mbps a resoluciones bajas, hasta 20 Mbps en 4K),
para que más resolución venga siempre acompañada de más bits con los que dibujarla — subir
solo la resolución sin subir el bitrate es lo que produce pixelado, no lo arregla. Siguen
siendo techos, no mínimos fijos: si la red no da para tanto, WebRTC reduce el bitrate
automáticamente para no cortar la transmisión, y reparte el recorte entre nitidez y
fluidez según haga falta en cada momento (nunca sacrifica siempre lo mismo) — se probaron
los dos extremos (priorizar solo nitidez, priorizar solo fluidez) y ambos se notaban mal
de una manera distinta con 4K de por medio: uno se veía nítido pero a saltos, el otro
fluido pero pixelado. Ninguno de estos ajustes le pide más al hardware del móvil que los
otros — es el mismo techo de bitrate y la misma resolución de captura, solo cambia cómo se
reparten cuando la red no da para todo.

Ten en cuenta que grabar en 4K exige bastante más al procesador/GPU del móvil que 1080p —
en emisiones largas puede notarse más calentamiento y más consumo de batería que antes. Si
prefieres priorizar autonomía/temperatura sobre nitidez máxima, se puede volver a bajar el
techo de resolución (`MAX_CAPTURE_WIDTH`/`MAX_CAPTURE_HEIGHT` en `WebRtcClient.kt`).

### Vista para el espectador

Quien ve la transmisión puede girar la imagen con los botones ⟲ (izquierda) y ⟳
(derecha) que aparecen sobre el vídeo — cada toque gira 90° más en ese sentido, tantas
veces como haga falta hasta encontrar el ángulo correcto (útil si quien emite sujeta el
móvil en un ángulo distinto al que le viene bien a quien mira). También tiene su propio
control de sonido (botón 🔊/🔇 + regla de volumen, abajo a la derecha del vídeo):
silenciar o graduar el volumen ahí es cosa de cada espectador, no afecta a los demás ni a
quien emite. Nada de esto cambia nada en el móvil que emite, solo cómo se ve/oye en ese
navegador en concreto.

Arriba a la izquierda y a la derecha del vídeo aparecen, solo cuando hace falta, dos
iconos de "sin señal" (una cámara 🎥 y un micrófono 🎙️, cada uno con una raya cruzada,
al estilo de Zoom/Meet): avisan de si quien emite tiene el vídeo y/o el micrófono
apagados en ese momento, en vez de dejar la imagen congelada sin explicación. Se
actualizan solos y al instante en cuanto quien emite cambia de fuente o de micrófono, sin
que el espectador tenga que recargar la página — esto SÍ viaja por el servidor de
señalización (a diferencia del vídeo/audio, que va directo), porque es justo el tipo de
aviso pequeño para el que existe ese servidor.

Si la sala tiene contraseña, el navegador la pide con un formulario propio en cuanto
detecta que hace falta (no hace falta compartir la contraseña dentro del enlace) —si la
escribe mal, puede volver a intentarlo sin recargar la página.

Una sala = un móvil emitiendo. Pueden verla varias personas a la vez.

**Compatibilidad con Safari:** la página del espectador incluye un arreglo para un fallo
conocido de Safari/WebKit con páginas que solo RECIBEN vídeo/audio sin mandar nada propio
(como esta) — sin ese arreglo, Safari a veces se queda sin conectar nunca, sin ningún error
visible, aunque en Chrome/Firefox funcione perfectamente. Si algún espectador en Safari
sigue sin ver nada después de esto, comprueba primero que abrió el enlace directamente en
Safari (no dentro del navegador integrado de WhatsApp/Instagram/etc. al tocar el enlace
desde esa app — ese navegador integrado no siempre soporta WebRTC igual de bien).

### Servidor TURN (Cloudflare Realtime)

Con redes normales (WiFi doméstica, datos móviles) la conexión directa entre el móvil y
quien mira funciona solo con los servidores STUN públicos incluidos. Pero en redes con NAT
restrictivo (datos móviles de algunos operadores, WiFis corporativas...) esa conexión
directa puede no llegar a establecerse nunca — se ve "En directo" pero la imagen queda en
negro y sin sonido, porque ninguno de los dos lados consigue encontrar al otro. Para esos
casos el servidor puede repartir credenciales de un servidor TURN (que hace de repetidor)
usando el servicio gratuito de Cloudflare Realtime (1000 GB/mes gratis):

1. Crea una "TURN Key" en el panel de Cloudflare (sección Realtime/Calls) — te da un
   **Key ID** y un **API Token**.
2. En Render (o donde tengas desplegado el servidor): **Environment → Add Environment
   Variable** y añade `CF_TURN_KEY_ID` y `CF_TURN_API_TOKEN` con esos valores. No los
   compartas ni los subas a git — solo viven como variables de entorno del servidor.
3. Redespliega el servicio para que recoja las nuevas variables.

Con eso configurado, tanto la app como `watch.html` piden automáticamente credenciales
TURN de corta duración a `/turn-credentials` antes de conectar, y las añaden a las que ya
tenían de STUN. El secreto (`CF_TURN_API_TOKEN`) nunca sale del servidor: lo único que
viaja al móvil o al navegador son credenciales ya generadas y de validez limitada (24h). Si
no configuras esas variables, todo sigue funcionando igual que antes, solo con STUN.

## Limitaciones conocidas

- **Una emisión por sala.** Si dos móviles intentan usar la misma sala a la vez, el
  segundo recibe un error. Usa nombres de sala distintos si vais a emitir varias
  personas.
- **La contraseña de sala es una protección básica**, no cifrado — evita que cualquiera
  que adivine el nombre de la sala pueda verla, pero el servidor la comprueba en texto
  plano (sin más cifrado que el de la propia conexión HTTPS/WSS). No la uses para
  contenido realmente sensible.
- No se ha podido compilar/ejecutar el proyecto en este entorno de trabajo (sin acceso a
  los repositorios de Android), así que, aunque el código se ha escrito con mucho
  cuidado siguiendo las APIs estándar de Android/WebRTC, es posible que necesites algún
  ajuste menor al compilarlo por primera vez en Android Studio.
