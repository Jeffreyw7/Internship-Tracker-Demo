# Run from any directory: powershell -ExecutionPolicy Bypass -File .\start.ps1
$ErrorActionPreference = 'Stop'

$appPort = if ($env:PORT) { $env:PORT } else { '8080' }
$appAddress = if ($env:SERVER_ADDRESS -and $env:SERVER_ADDRESS -ne '0.0.0.0') {
    $env:SERVER_ADDRESS
} else { '127.0.0.1' }
if ($appAddress.Contains(':')) { $appAddress = "[$appAddress]" }
$appUrl = "http://${appAddress}:$appPort"
$health = $null
try {
    $health = Invoke-RestMethod "$appUrl/api/health" -TimeoutSec 3
} catch {
    # No healthy instance detected; let Spring Boot report startup errors.
}
if ($health.application -eq 'campus-tracker' -and $health.status -eq 'UP') {
    Write-Host "Campus Tracker is already running at $appUrl"
    Write-Host 'To restart, press Ctrl+C in its original terminal first.'
    exit 0
}

if (-not (Test-Path -LiteralPath "$PSScriptRoot/dist/index.html")) {
    throw 'Build the frontend first: run npm ci and npm run build in the project root.'
}

Push-Location "$PSScriptRoot/backend"
try {
    & .\mvnw.cmd -Pwith-ui spring-boot:run
    $launchExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($launchExitCode -ne 0) {
    Write-Host 'Startup failed. Look above Maven''s exit-code summary for the underlying error.'
    Write-Host 'If campus.mv.db is locked, stop the existing Campus Tracker instance with Ctrl+C. Keep the database files.'
}
exit $launchExitCode
