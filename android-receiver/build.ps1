$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
if ($env:LOCALAPPDATA) { $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
$sdk = $sdkCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms\android-35\android.jar')) } | Select-Object -First 1
if (-not $sdk) { throw 'No se encontró Android SDK 35. Configura ANDROID_SDK_ROOT.' }
$buildTools = Join-Path $sdk 'build-tools\37.0.0'
$platform = Join-Path $sdk 'platforms\android-35\android.jar'
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += (Join-Path $env:JAVA_HOME 'bin') }
if ($javacCommand) { $jdkCandidates += (Split-Path -Parent $javacCommand.Source) }
$jdkCandidates += 'C:\Program Files\Java\jdk-21\bin'
$jdk = $jdkCandidates | Where-Object {
  (Test-Path (Join-Path $_ 'javac.exe')) -and (Test-Path (Join-Path $_ 'jar.exe')) -and (Test-Path (Join-Path $_ 'keytool.exe'))
} | Select-Object -First 1
if (-not $jdk) { throw 'Instala JDK 21 y configura JAVA_HOME o agrega javac.exe al PATH.' }
$out = Join-Path $project 'build'
$classes = Join-Path $out 'classes'

foreach ($path in @((Join-Path $buildTools 'aapt2.exe'), (Join-Path $buildTools 'd8.bat'), (Join-Path $buildTools 'zipalign.exe'), (Join-Path $buildTools 'apksigner.bat'), $platform, (Join-Path $jdk 'javac.exe'), (Join-Path $jdk 'keytool.exe'))) {
  if (-not (Test-Path $path)) { throw "Falta herramienta: $path" }
}
$dex = Join-Path $out 'dex'
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Force $out | Out-Null }
$resolvedOut = (Resolve-Path $out).Path.TrimEnd('\')
if ($resolvedOut -ne (Join-Path $project 'build')) { throw "Carpeta de compilación inesperada: $resolvedOut" }
if (Test-Path $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
if (Test-Path $dex) { Remove-Item -LiteralPath $dex -Recurse -Force }
foreach ($generatedFile in @((Join-Path $out 'receiver-unsigned.apk'), (Join-Path $out 'receiver-aligned.apk'), (Join-Path $out 'classes.dex'), (Join-Path $out 'resources.zip'), (Join-Path $out 'ProyectorReceiver.apk'))) {
  if (Test-Path $generatedFile) { Remove-Item -LiteralPath $generatedFile -Force }
}
New-Item -ItemType Directory -Force $classes, $dex | Out-Null

$unsigned = Join-Path $out 'receiver-unsigned.apk'
$compiledResources = Join-Path $out 'resources.zip'
& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $project 'res') -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'aapt2 no pudo compilar el icono' }
& (Join-Path $buildTools 'aapt2.exe') link -R $compiledResources --manifest (Join-Path $project 'AndroidManifest.xml') -I $platform --min-sdk-version 23 --target-sdk-version 35 --version-code 9 --version-name 1.8 -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'aapt2 no pudo crear el APK base' }

$source = Join-Path $project 'src\com\proyector\receiver\MainActivity.java'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $platform -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'javac no pudo compilar la APK' }
& (Join-Path $buildTools 'd8.bat') --lib $platform --output $dex (Get-ChildItem $classes -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
if ($LASTEXITCODE -ne 0) { throw 'd8 no pudo generar classes.dex' }
Copy-Item (Join-Path $dex 'classes.dex') (Join-Path $out 'classes.dex')
Push-Location $out
try { & (Join-Path $jdk 'jar.exe') uf $unsigned classes.dex; if ($LASTEXITCODE -ne 0) { throw 'jar no pudo insertar classes.dex' } } finally { Pop-Location }

$aligned = Join-Path $out 'receiver-aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign no pudo alinear el APK' }
$keystore = Join-Path $out 'proyector.keystore'
if (-not (Test-Path $keystore)) {
  & (Join-Path $jdk 'keytool.exe') -genkeypair -keystore $keystore -storepass android -keypass android -alias proyector -dname 'CN=Proyector Receiver' -keyalg RSA -keysize 2048 -validity 10000
}
$apk = Join-Path $out 'ProyectorReceiver.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $keystore --ks-key-alias proyector --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner no pudo firmar el APK' }
Write-Output "Creado: $apk"
