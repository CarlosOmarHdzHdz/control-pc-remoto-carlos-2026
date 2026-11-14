$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $project 'build\ProyectorSender.jar'
if (-not (Test-Path $jar)) { & (Join-Path $project 'build.ps1') }
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += (Join-Path $env:JAVA_HOME 'bin') }
if ($javacCommand) { $jdkCandidates += (Split-Path -Parent $javacCommand.Source) }
$jdkCandidates += 'C:\Program Files\Java\jdk-21\bin'
$jdk = $jdkCandidates | Where-Object { Test-Path (Join-Path $_ 'java.exe') } | Select-Object -First 1
if (-not $jdk) { throw 'Instala JDK 21 y configura JAVA_HOME o agrega java.exe al PATH.' }
& (Join-Path $jdk 'java.exe') -jar $jar
