# Control de PC

Mira el escritorio completo de Windows desde Android y manéjalo con la pantalla táctil. Conecta los equipos por Wi-Fi, por cable USB o mediante Tailscale si están en redes distintas.

## Descargar e instalar

- **Windows 10/11 de 64 bits.** Descarga el ZIP, extrae la carpeta completa y abre `ProyectorSender\ProyectorSender.exe`. El paquete trae su propio entorno Java. Conserva `app` y `runtime` junto al ejecutable.
- **Android 6 o posterior.** Descarga e instala `ProyectorReceiver-1.10.0.apk`.

[Descargar Control de PC 1.10.0](https://github.com/CarlosOmarHdzHdz/control-pc-remoto-carlos-2026/releases/tag/v1.10.0)

Actualiza ambos equipos a la versión 1.10.0 para utilizar todos los atajos nuevos. Esta edición conserva el enlace cifrado y la reconexión entre direcciones IP.

## Conectar

1. En Windows, pulsa **Iniciar emisor**.
2. En Android, abre **Conexión**, elige el modo, escribe el código de seis dígitos de Windows y pulsa **Conectar**.
3. La primera vez, compara los cuatro grupos de **Identidad de este equipo** que aparecen en ambas pantallas. Pulsa **Coincide** si son iguales.

| Modo | Qué necesitas |
| --- | --- |
| **Wi-Fi / LAN** | Conecta ambos equipos a la misma red privada. Android busca la PC automáticamente. Si el router bloquea la detección, escribe la IP que muestra el emisor. |
| **Cable USB** | Usa un cable de datos y activa **Compartir Internet por USB** en Android. El botón **Abrir ajustes de USB** te lleva al ajuste disponible en tu dispositivo. Si la detección no aparece, escribe la IP de la interfaz USB que muestra Windows. |
| **Otra red** | Instala Tailscale en ambos equipos, conéctalos a la misma red privada y escribe en Android la IP que Windows identifica como **Tailscale**. La PC debe estar encendida con el emisor activo. |

Si ya usas ADB con la PC autorizada, puedes elegir **USB directo (ADB)**. Con el puerto predeterminado, ejecuta en Windows `adb reverse tcp:5050 tcp:5050` y conecta Android a `127.0.0.1:5050`. El modo **Cable USB** mediante conexión compartida funciona sin depuración USB cuando el teléfono ofrece esa opción.

Cuando un enlace activo se interrumpe, la app intenta recuperarlo. En el modo con detección automática, acepta la nueva IP de la misma PC cuando recibe su anuncio y verifica que su identidad siga siendo la misma. Si reinicias el emisor, usa el código nuevo.

## Escritorio, imagen y controles

- **Control directo.** Toca los elementos del escritorio y arrástralos en la imagen. El **panel táctil** mueve el cursor al deslizar, hace clic al tocar y permite arrastrar con doble toque y pulsación mantenida.
- **Gestos.** Dos dedos desplazan la página, una pulsación mantenida hace clic derecho y tres dedos abren el teclado. Usa Atrás en Android para salir del escritorio a pantalla completa.
- **Imagen.** Elige hasta 1080p y un límite de hasta 60 FPS en **Remoto** o cambia de una vez entre **Ahorro**, **Equilibrado**, **Fluido** y **Full HD**. Activa el ajuste automático para que la app reduzca resolución y FPS si el enlace se satura. La resolución y los FPS reales dependen de Windows, Android y la red. La codificación JPEG usa la CPU. La captura se pausa al volver al menú o dejar la app en segundo plano.
- **Panel táctil.** Ajusta su sensibilidad entre 50 % y 300 % para adaptar el desplazamiento del cursor a tu pantalla.
- **Atajos y multimedia.** Envía los atajos limitados **Win+D**, **Alt+Tab** y **Ctrl+Shift+Esc**, y controla reproducción, pista anterior y siguiente sin salir del escritorio.
- **Audio y energía.** Consulta el volumen real y la batería de Windows. Sube o baja el nivel en pasos de 5 %, silencia, reactiva o fíjalo con el deslizador. Puedes bloquear, suspender, reiniciar o apagar la PC. Suspensión, reinicio y apagado requieren confirmación en Android.

## Segundo monitor

En el emisor, abre **Configuración de pantallas**. Si Windows tiene una pantalla adicional activa, selecciona **Extender** y elige esa pantalla en **Segundo monitor**. Abre **Monitor** en Android para ver esa pantalla a tamaño completo y arrastrar ventanas hacia ella desde la PC.

Para crear una pantalla virtual en una computadora que solo tiene una pantalla activa, Windows necesita un controlador de pantalla firmado. Este paquete admite pantallas adicionales que Windows ya reconoce. [Modelo de controlador de pantalla indirecta de Microsoft](https://learn.microsoft.com/en-us/windows-hardware/drivers/display/indirect-display-driver-model-overview).

## Seguridad

El escritorio y los controles viajan por TLS 1.2 o 1.3 en los tres modos. Android comprueba la identidad que confirmaste al vincular y nunca envía el PIN a un equipo cuya identidad no hayas verificado. El emisor genera un PIN nuevo al iniciar, limita los intentos y mantiene una sola sesión autorizada a la vez. La app recuerda la identidad de Windows y mantiene el PIN en memoria durante el enlace.

Usa **Otra red** con Tailscale para conectarte desde Internet. Mantén el puerto del emisor sin reenvío público en el router. Si cambias de PC o reinstalas el sistema, usa **Olvidar PC vinculada** en Android y compara la identidad de nuevo.

## Compilar desde el código

Usa JDK 21, .NET Framework 4 en Windows y Android SDK 35 con Build Tools 37.0.0. Los scripts aceptan `JAVA_HOME` y `ANDROID_SDK_ROOT`.

```powershell
& .\pc-sender\build.ps1
& .\pc-sender\package.ps1
& .\android-receiver\build.ps1
```

La clave local de firma de Android queda en `android-receiver\build\proyector.keystore`. Conserva la clave original en un lugar privado para firmar futuras actualizaciones.
