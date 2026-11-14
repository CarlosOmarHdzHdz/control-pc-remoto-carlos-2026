$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $project 'build\ProyectorSender.jar'))) { & (Join-Path $project 'build.ps1') }
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += (Join-Path $env:JAVA_HOME 'bin') }
if ($javacCommand) { $jdkCandidates += (Split-Path -Parent $javacCommand.Source) }
$jdkCandidates += 'C:\Program Files\Java\jdk-21\bin'
$jdk = $jdkCandidates | Where-Object { (Test-Path (Join-Path $_ 'javac.exe')) -and (Test-Path (Join-Path $_ 'jpackage.exe')) } | Select-Object -First 1
if (-not $jdk) { throw 'Instala JDK 21 y configura JAVA_HOME o agrega jpackage.exe al PATH.' }
$dist = Join-Path $project 'release-v1.7'
$packageInput = Join-Path $project 'build\package-input'
$compatibilityDist = Join-Path $project 'build\dist-v1.7'
if (Test-Path $dist) {
  $resolvedDist = (Resolve-Path $dist).Path.TrimEnd('\')
  if ($resolvedDist -ne (Join-Path $project 'release-v1.7')) { throw "Carpeta de distribución inesperada: $resolvedDist" }
  Remove-Item -LiteralPath $dist -Recurse -Force
}
if (Test-Path $packageInput) {
  $resolvedInput = (Resolve-Path $packageInput).Path.TrimEnd('\')
  if ($resolvedInput -ne (Join-Path $project 'build\package-input')) { throw "Carpeta de entrada inesperada: $resolvedInput" }
  Remove-Item -LiteralPath $packageInput -Recurse -Force
}
New-Item -ItemType Directory -Force $dist, $packageInput | Out-Null
Copy-Item (Join-Path $project 'build\ProyectorSender.jar') (Join-Path $packageInput 'ProyectorSender.jar')
& (Join-Path $jdk 'jpackage.exe') --type app-image --name ProyectorSender --input $packageInput --main-jar ProyectorSender.jar --main-class com.proyector.sender.Main --dest $dist --app-version 1.7.0 --vendor 'Proyector local'
if ($LASTEXITCODE -ne 0) { throw 'jpackage no pudo crear la aplicación de Windows' }
if (-not (Test-Path (Join-Path $dist 'ProyectorSender\app\ProyectorSender.jar'))) { throw 'El paquete de Windows quedó incompleto: no incluye el programa principal.' }
if (Test-Path $compatibilityDist) {
  $resolvedCompatibility = (Resolve-Path $compatibilityDist).Path.TrimEnd('\')
  if ($resolvedCompatibility -ne (Join-Path $project 'build\dist-v1.7')) { throw "Carpeta de compatibilidad inesperada: $resolvedCompatibility" }
  Remove-Item -LiteralPath $compatibilityDist -Recurse -Force
}
New-Item -ItemType Directory -Force $compatibilityDist | Out-Null
Copy-Item -LiteralPath (Join-Path $dist 'ProyectorSender') -Destination $compatibilityDist -Recurse
if (-not (Test-Path (Join-Path $compatibilityDist 'ProyectorSender\app\ProyectorSender.jar'))) { throw 'La copia de compatibilidad quedó incompleta.' }
Write-Output "Creado: $(Join-Path $dist 'ProyectorSender\ProyectorSender.exe')"
Write-Output "Ruta anterior actualizada: $(Join-Path $compatibilityDist 'ProyectorSender\ProyectorSender.exe')"
