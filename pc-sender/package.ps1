param([string]$RuntimeImage)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $project 'build\ProyectorSender.jar'))) { & (Join-Path $project 'build.ps1') }
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += (Join-Path $env:JAVA_HOME 'bin') }
if ($javacCommand) { $jdkCandidates += (Split-Path -Parent $javacCommand.Source) }
$jdkCandidates += 'C:\Program Files\Java\jdk-21\bin'
$jdk = $jdkCandidates | Where-Object { (Test-Path (Join-Path $_ 'java.exe')) -and (Test-Path (Join-Path $_ 'javac.exe')) -and (Test-Path (Join-Path $_ 'jpackage.exe')) } | Select-Object -First 1
if (-not $jdk) { throw 'Instala JDK 21 y configura JAVA_HOME o agrega jpackage.exe al PATH.' }
$dist = Join-Path $project 'release-v1.11.0'
$packageInput = Join-Path $project 'build\package-input'
if (Test-Path $dist) {
  $resolvedDist = (Resolve-Path $dist).Path.TrimEnd('\')
  if ($resolvedDist -ne (Join-Path $project 'release-v1.11.0')) { throw "Carpeta de distribución inesperada: $resolvedDist" }
  Remove-Item -LiteralPath $dist -Recurse -Force
}
if (Test-Path $packageInput) {
  $resolvedInput = (Resolve-Path $packageInput).Path.TrimEnd('\')
  if ($resolvedInput -ne (Join-Path $project 'build\package-input')) { throw "Carpeta de entrada inesperada: $resolvedInput" }
  Remove-Item -LiteralPath $packageInput -Recurse -Force
}
New-Item -ItemType Directory -Force $dist, $packageInput | Out-Null
Copy-Item (Join-Path $project 'build\ProyectorSender.jar') (Join-Path $packageInput 'ProyectorSender.jar')
$icon = Join-Path $project 'build\Orbivia.ico'
& (Join-Path $jdk 'java.exe') '-Djava.awt.headless=true' -jar (Join-Path $packageInput 'ProyectorSender.jar') --write-icon $icon
if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el icono de Orbivía.' }
$runtimeOptions = @('--add-modules', 'java.desktop,jdk.crypto.ec', '--jlink-options', '--strip-debug --no-header-files --no-man-pages --compress=zip-6')
if ($RuntimeImage) {
  $RuntimeImage = (Resolve-Path -LiteralPath $RuntimeImage).Path
  if (-not (Test-Path -LiteralPath (Join-Path $RuntimeImage 'lib\modules'))) { throw 'Entorno Java portable incorrecto.' }
  $runtimeOptions = @('--runtime-image', $RuntimeImage)
}
& (Join-Path $jdk 'jpackage.exe') --type app-image --name Orbivia --icon $icon --input $packageInput --main-jar ProyectorSender.jar --main-class com.proyector.sender.Main --dest $dist --app-version 1.11.0 --vendor 'Orbivia' @runtimeOptions
if ($LASTEXITCODE -ne 0) { throw 'jpackage no pudo crear la aplicación de Windows' }
if (-not (Test-Path (Join-Path $dist 'Orbivia\app\ProyectorSender.jar'))) { throw 'El paquete de Windows quedó incompleto: no incluye el programa principal.' }
Write-Output "Creado: $(Join-Path $dist 'Orbivia\Orbivia.exe')"
