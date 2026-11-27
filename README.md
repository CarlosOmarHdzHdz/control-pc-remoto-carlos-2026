# Orbivía

Mira el escritorio completo de Windows desde Android y manéjalo con la pantalla táctil. Conecta los equipos por Wi-Fi, por cable USB o mediante Tailscale si están en redes distintas.

## Descargar e instalar

- **Windows 10/11 de 64 bits.** El instalador guarda Orbivía en tu perfil y añade accesos al escritorio y a Inicio. Abre la aplicación y haz clic derecho en su icono para elegir **Anclar a la barra de tareas**. Usa **Minimizar** para mantener el emisor en la barra de tareas. La **X** y **Salir** cierran Orbivía y desconectan el emisor. [Descargar el instalador para Windows](https://raw.githubusercontent.com/CarlosOmarHdzHdz/control-pc-remoto-carlos-2026/v1.15.1/downloads/Orbivia-Windows-1.15.1.msi)
- **Android 6 o posterior.** Instala la APK sobre la versión anterior para conservar tu PC vinculada. [Descargar Orbivía para Android](https://raw.githubusercontent.com/CarlosOmarHdzHdz/control-pc-remoto-carlos-2026/v1.15.0/downloads/Orbivia-Android-1.15.0.apk)

En Android, toca el icono de apariencia junto al estado de conexión. En Windows, usa el selector en la parte superior. Ambos recuerdan tu elección entre **Sistema**, **Blanco** y **Oscuro**. En Inicio, toca la batería para consultar ambos equipos, el volumen para abrir los controles o la latencia para ir al gráfico. Dentro del escritorio, abre **Controles del visor** para cambiar entre directo, panel táctil y Solo ver. Desde ahí también puedes girar, abrir el teclado, guardar una captura o cambiar de pantalla. [Sumas SHA-256](downloads/SHA256SUMS-1.15.1.txt)

## Conectar

1. En Windows, pulsa **Iniciar emisor**.
2. En Android, abre **Conexión**, elige el modo, escribe el código de seis dígitos de Windows y pulsa **Conectar**.
3. La primera vez, compara los cuatro grupos de **Identidad de este equipo** que aparecen en ambas pantallas. Pulsa **Coincide** si son iguales.

| Modo | Qué necesitas |
| --- | --- |
| **Wi-Fi / LAN** | Conecta ambos equipos a la misma red privada. Android busca la PC automáticamente. Si el router bloquea la detección, escribe la IP que muestra el emisor. |
| **Cable USB** | Usa un cable de datos y activa **Compartir Internet por USB** en Android. El botón **Abrir ajustes de USB** te lleva al ajuste disponible en tu dispositivo. Si la detección no aparece, escribe la IP de la interfaz USB que muestra Windows. |
| **Otra red** | Abre Tailscale en ambos equipos con la misma red privada. En **Red** del emisor, copia la dirección **Tailscale**. En Android, **Otra red → Pegar dirección de la PC** admite la IP y el puerto juntos. También puedes escribirlos. La PC debe estar encendida con el emisor activo. |

**Cable de datos con depuración USB.** Si ya usas Android Platform Tools, conecta un único teléfono por USB y autoriza esta PC en Android. En el emisor iniciado abre **Red → Preparar cable ADB**. En Android elige **Cable USB → Usar USB directo (ADB)** e introduce el código. Orbivía prepara el puerto automáticamente aunque el emisor use un puerto distinto de 5050. También puedes usar conexión compartida sin instalar ADB.

Si vinculas Android mientras Tailscale está activo en Windows, Orbivía guarda la dirección privada de esa PC. Al elegir **Otra red**, puedes usarla con el código que muestra el emisor. Configura Tailscale en ambos equipos. [Instalar Tailscale en Android](https://tailscale.com/docs/install/android)

Cuando un enlace activo se interrumpe, la app intenta recuperarlo. En el modo con detección automática, acepta la nueva IP de la misma PC cuando recibe su anuncio y verifica que su identidad siga siendo la misma. Si reinicias el emisor, usa el código nuevo.

## Escritorio, imagen y controles

- **Control directo.** Toca los elementos del escritorio y arrástralos en la imagen. En **Panel táctil**, desliza el dedo hasta dejar el cursor sobre el elemento. Toca una vez o pulsa **Clic** para seleccionarlo. Pulsa **Doble clic** para abrir una carpeta o **Derecho** para abrir su menú. Para arrastrar, toca dos veces y mueve el dedo durante el segundo toque.
- **Solo ver.** Pellizca para ampliar hasta 4× y arrastra para recorrer la imagen. Los gestos de este modo actúan sobre la vista de Android.
- **Controles del visor.** Abre el panel superior para ver los FPS recibidos y la latencia. Toca **Captura** para guardar un PNG. En **Ajustes** puedes cambiar el perfil de imagen o regular el volumen.
- **Gestos.** Dos dedos desplazan la página, una pulsación mantenida hace clic derecho y tres dedos abren el teclado. Usa Atrás en Android para salir del escritorio a pantalla completa.
- **Imagen.** Elige hasta 1080p y un límite de hasta 60 FPS en **Remoto** o cambia de una vez entre **Ahorro**, **Equilibrado**, **Fluido** y **Full HD**. Activa el ajuste automático para que la app reduzca resolución y FPS si el enlace se satura. La resolución y los FPS reales dependen de Windows, Android y la red. La codificación JPEG usa la CPU. La captura se pausa al volver al menú o dejar la app en segundo plano.
- **Panel táctil.** Ajusta su sensibilidad entre 50 % y 300 % para adaptar el desplazamiento del cursor a tu pantalla.
- **Atajos y multimedia.** Envía los atajos limitados **Win+D**, **Alt+Tab** y **Ctrl+Shift+Esc**, y controla reproducción, pista anterior y siguiente sin salir del escritorio.
- **Audio y energía.** Consulta el volumen real y la batería de Windows. Sube o baja el nivel en pasos de 5 %, silencia, reactiva o fíjalo con el deslizador. Puedes bloquear, suspender, reiniciar o apagar la PC. Suspensión, reinicio y apagado requieren confirmación en Android.

## Segundo monitor

1. Conecta Orbivía y abre **Monitor** en Android. Si Windows ya tiene una segunda pantalla activa, toca **Abrir segundo monitor**. Si estás preparando una pantalla, toca **Abrir cuando esté lista** y deja esa pestaña abierta.
2. Si la PC solo tiene una pantalla, ve a **Pantallas → Preparar monitor virtual** en Orbivía para Windows. La primera vez WinGet obtiene **Virtual Driver Control**, que requiere Internet. En esa herramienta elige el controlador de pantalla y pulsa **Install**. Windows puede solicitar permisos de administrador. Si WinGet no está disponible, el asistente ofrece la descarga oficial. [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/latest)
3. Pulsa **Extender escritorio** en el emisor. Si Windows detecta varias pantallas adicionales, elige la que quieras mostrar en el teléfono. Para organizar su posición en Windows, abre **Configuración de pantallas**. Mueve la ventana activa a la pantalla contigua con **Win + Mayús + Flecha**. Para retirar el controlador virtual, abre Virtual Driver Control y elige **Uninstall**.

## Seguridad

El escritorio y los controles viajan por TLS 1.2 o 1.3 en los tres modos. Android comprueba la identidad que confirmaste al vincular y nunca envía el PIN a un equipo cuya identidad no hayas verificado. El emisor genera un PIN nuevo al iniciar, limita los intentos y mantiene una sola sesión autorizada a la vez. La app recuerda la identidad de Windows y mantiene el PIN en memoria durante el enlace.

Usa **Otra red** con Tailscale para conectarte desde Internet. Mantén el puerto del emisor sin reenvío público en el router. Si cambias de PC o reinstalas el sistema, usa **Olvidar PC vinculada** en Android y compara la identidad de nuevo.

## Compilar desde el código

Usa JDK 21, .NET Framework 4 en Windows y Android SDK 35 con Build Tools 37.0.0. La creación del MSI también requiere WiX 3.14 en el PATH. Los scripts aceptan `JAVA_HOME` y `ANDROID_SDK_ROOT`.

```powershell
& .\pc-sender\build.ps1
& .\pc-sender\package.ps1 -Installer
& .\android-receiver\build.ps1
```

La clave local de firma de Android queda en `android-receiver\build\proyector.keystore`. Conserva la clave original en un lugar privado para firmar futuras actualizaciones.
