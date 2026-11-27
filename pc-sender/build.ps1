$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += (Join-Path $env:JAVA_HOME 'bin') }
if ($javacCommand) { $jdkCandidates += (Split-Path -Parent $javacCommand.Source) }
$jdkCandidates += 'C:\Program Files\Java\jdk-21\bin'
$jdk = $jdkCandidates | Where-Object { (Test-Path (Join-Path $_ 'javac.exe')) -and (Test-Path (Join-Path $_ 'jar.exe')) } | Select-Object -First 1
if (-not $jdk) { throw 'Instala JDK 21 y configura JAVA_HOME o agrega javac.exe al PATH.' }
$out = Join-Path $project 'build'
$classes = Join-Path $out 'classes'

if (-not (Test-Path (Join-Path $jdk 'javac.exe'))) { throw "No se encontró el JDK en $jdk" }
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Force $out | Out-Null }
$resolvedOut = (Resolve-Path $out).Path.TrimEnd('\')
if ($resolvedOut -ne (Join-Path $project 'build')) { throw "Carpeta de salida inesperada: $resolvedOut" }
if (Test-Path $classes) {
  if ((Resolve-Path -LiteralPath $classes).Path -ne (Join-Path $resolvedOut 'classes')) { throw 'Directorio de clases inesperado.' }
  Remove-Item -LiteralPath $classes -Recurse -Force
}
$jar = Join-Path $out 'ProyectorSender.jar'
if (Test-Path $jar) { Remove-Item -LiteralPath $jar -Force }
New-Item -ItemType Directory -Force $classes | Out-Null

$sources = @(Get-ChildItem -Path (Join-Path $project 'src'), (Join-Path $project '..\shared\src') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 21 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac no pudo compilar el emisor de Windows' }
$native = Join-Path $classes 'native'
New-Item -ItemType Directory -Force -Path $native | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Falta el compilador de .NET Framework 4 en Windows.' }
$helper = Join-Path $native 'WindowsControls.exe'
& $compiler /nologo /codepage:65001 /target:winexe /optimize+ "/out:$helper" (Join-Path $project 'windows\WindowsControls.cs')
if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar el componente de audio y energía de Windows.' }
& (Join-Path $jdk 'jar.exe') cfe $jar com.proyector.sender.Main -C $classes . -C (Join-Path $project '..\shared\art') studio_devices.png
if ($LASTEXITCODE -ne 0) { throw 'jar no pudo crear el ejecutable de Java' }
Write-Output "Creado: $jar"
