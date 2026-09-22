# Run from any directory: powershell -ExecutionPolicy Bypass -File .\start.ps1
$ErrorActionPreference = 'Stop'

# Derive the local URL from environment settings used by the Java server.
$appPort = if ($env:PORT) { $env:PORT } else { '8080' }
$appAddress = if ($env:SERVER_ADDRESS -and $env:SERVER_ADDRESS -ne '0.0.0.0') {
    $env:SERVER_ADDRESS
} else { '127.0.0.1' }
if ($appAddress.Contains(':')) { $appAddress = "[$appAddress]" }
$appUrl = "http://${appAddress}:$appPort"
# Check whether this port already hosts a healthy Campus instance before launching another.
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

# Require built frontend assets because the with-ui Maven profile bundles dist into Java.
if (-not (Test-Path -LiteralPath "$PSScriptRoot/dist/index.html")) {
    throw 'Build the frontend first: run npm ci and npm run build in the project root.'
}

# Launch from backend so relative database paths resolve consistently, then restore the shell location.
Push-Location "$PSScriptRoot/backend"
try {
    & .\mvnw.cmd -Pwith-ui spring-boot:run
    $launchExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
# Surface startup failures and explain how to handle the common database-lock case.
if ($launchExitCode -ne 0) {
    Write-Host 'Startup failed. Look above Maven''s exit-code summary for the underlying error.'
    Write-Host 'If campus.mv.db is locked, stop the existing Campus Tracker instance with Ctrl+C. Keep the database files.'
}
exit $launchExitCode
