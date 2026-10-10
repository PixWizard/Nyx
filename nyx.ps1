$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

$JarPath = Join-Path $ScriptDir "target\nyx-stegano-1.0-SNAPSHOT.jar"
$LibPath = Join-Path $ScriptDir "target\lib"

if (-not (Test-Path $JarPath) -or -not (Test-Path $LibPath)) {
    Write-Host "[*] Nyx binary or dependencies not found. Building project..." -ForegroundColor Cyan
    mvn package dependency:copy-dependencies -DoutputDirectory=target/lib -DskipTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$ModulePath = "$JarPath;$LibPath"
java --module-path $ModulePath -m org.ghost/org.ghost.Main $args
