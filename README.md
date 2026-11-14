# Control de PC · Proyector

Aplicación Android y emisor para Windows para ver y controlar el escritorio completo. La interfaz mantiene dos modos claros: red local o una red privada VPN para conectar desde otra ubicación.

## Descargas

Los archivos están en la [versión 1.8](https://github.com/CarlosOmarHdzHdz/proyector-control-pc/releases/tag/v1.8):

- [`ProyectorReceiver-1.8.apk`](downloads/ProyectorReceiver-1.8.apk): aplicación Android (Android 6 o posterior).
- [`ProyectorSender-Windows-1.8.zip`](downloads/ProyectorSender-Windows-1.8.zip): aplicación portable para Windows 10/11. Extrae la carpeta y ejecuta `ProyectorSender.exe`; conserva junto al EXE las carpetas `app` y `runtime`.
- [`SHA256SUMS-1.8.txt`](downloads/SHA256SUMS-1.8.txt): hashes SHA‑256 para verificar que las descargas estén completas.

## Red local y USB

1. Abre el emisor en Windows, pulsa **Iniciar emisor** y conserva visible el código de seis dígitos.
2. Conecta ambos equipos al mismo Wi‑Fi o conecta el teléfono y activa **Compartir Internet por USB**.
3. En Android escribe el código y pulsa **Conectar**. En Wi‑Fi, la PC se encuentra automáticamente; por USB, selecciona **Conexión** y sigue la tarjeta **Cable USB**.

Un cable que solo carga no transporta red. Para USB directo con ADB, activa Depuración USB y ejecuta en Windows `adb reverse tcp:5050 tcp:5050`; la APK conecta a `127.0.0.1:5050`.

## Conectar desde otra red

La aplicación no abre puertos públicos. Para conectar desde datos móviles, otra Wi‑Fi o una ubicación distinta:

1. Instala Tailscale en Windows y Android e inicia sesión en la misma red privada (tailnet).
2. Inicia el emisor. En su lista de direcciones copia la marcada **Tailscale · VPN** (empieza por `100.`).
3. En Android pulsa **Redes diferentes · VPN privada**, introduce esa IP y el código de seis dígitos.

Tailscale conecta los dispositivos como si compartieran una red privada y cifra el túnel con WireGuard. El modo remoto acepta solo direcciones Tailscale `100.64.0.0/10`; no configures reenvío del puerto 5050 en el router. Ambos dispositivos deben tener conexión a Internet y Tailscale activo. La disponibilidad y velocidad dependen de las redes; conexiones difíciles pueden usar el relay cifrado del servicio VPN.

## Controles y rendimiento

- Ver el escritorio completo, interacción táctil y panel táctil.
- Bloquear, suspender, apagar, reiniciar y ajustar el volumen de Windows.
- Muestra el porcentaje de batería de Windows y del Android, y el volumen real de Windows. El control deslizante fija el volumen; los botones lo cambian en pasos de 5 %.
- Ajustes de imagen de 360p, 540p, 720p y Full HD 1080p; FPS seleccionables hasta 60. Para reducir latencia en redes lentas, empieza con **360p/540p y 15–24 FPS**. 1080p/60 depende de la captura, CPU, Wi‑Fi y teléfono; no se puede garantizar ese ritmo en cualquier equipo.
- La captura usa una imagen JPEG por fotograma y CPU; no usa codificación de vídeo por hardware. En esta versión se redujo la captura adicional de alta resolución para evitar trabajo que no mejora la imagen final y añade demora.

## Seguridad

El código aleatorio de seis dígitos cambia al iniciar el emisor; cinco intentos incorrectos bloquean la dirección durante un minuto. Windows rechaza conexiones públicas y solo admite red privada, loopback, link‑local o el rango Tailscale. No compartas el código ni el acceso al tailnet con personas no confiables.

En la conexión directa de red local, el protocolo TCP de la aplicación todavía no cifra por sí mismo; úsalo en una red privada de confianza. En el modo de redes distintas, el tráfico va dentro del túnel cifrado de Tailscale. No publiques ni redirijas el puerto 5050 a Internet.

## Segundo monitor extendido

La opción permanece separada de **Ver escritorio** y **Panel táctil**, pero Windows aún necesita un controlador de pantalla virtual firmado para reconocer Android como monitor extendido. La app no instala ni incluye ese controlador; la captura remota no crea una pantalla adicional de Windows.

## Compilar

Requisitos locales: JDK 21 y Android SDK 35. Configura `JAVA_HOME` y `ANDROID_SDK_ROOT` (Build Tools 37.0.0). Desde la carpeta del proyecto:

```powershell
& .\pc-sender\build.ps1
& .\pc-sender\package.ps1
& .\android-receiver\build.ps1
```

Las salidas de compilación y la clave local `android-receiver\build\proyector.keystore` quedan fuera de Git. Conserva esa clave privada para firmar futuras actualizaciones de la APK. Las versiones descargables se adjuntan en Releases.
