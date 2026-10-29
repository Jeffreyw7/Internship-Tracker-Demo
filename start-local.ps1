# Free local app + capture inbox. No Docker, cloud account or external SMTP credentials.
[CmdletBinding()]
param(
    [ValidateRange(1024,65535)][int]$Port = 8080,
    [ValidateRange(1024,65535)][int]$InboxPort = 8025,
    [ValidateRange(1024,65535)][int]$SmtpPort = 1025,
    [string]$DataDirectory,
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
if (-not $DataDirectory) { $DataDirectory = Join-Path $PSScriptRoot 'backend/data/local-password-reset' }

if (@($Port,$InboxPort,$SmtpPort | Select-Object -Unique).Count -ne 3) {
    throw 'Choose three different ports for the app, inbox and SMTP listener.'
}
$jar = Join-Path $PSScriptRoot 'backend/target/campus-tracker-2.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build first: npm.cmd ci; npm.cmd run build; then from backend run .\mvnw.cmd -Pwith-ui package.'
}
$java = (Get-Command java -ErrorAction Stop).Source
$toolsDirectory = Join-Path $PSScriptRoot 'backend/target/local-tools/mailpit-v1.31.1'
$mailpit = Join-Path $toolsDirectory 'mailpit.exe'
if (-not (Test-Path -LiteralPath $mailpit)) {
    New-Item -ItemType Directory -Path $toolsDirectory -Force | Out-Null
    $archive = Join-Path $toolsDirectory 'mailpit.zip'
    $previousProgress = $ProgressPreference
    try {
        $ProgressPreference = 'SilentlyContinue'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/axllent/mailpit/releases/download/v1.31.1/mailpit-windows-amd64.zip' -OutFile $archive
        # Published SHA-256 digest of the pinned official GitHub release asset.
        $expected = '73ff05204741bd89cc96e6074573c4b22e1edfadf1b3427536233ff20c751604'
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
            throw 'Mailpit archive checksum mismatch; refusing to run it.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
    } finally { $ProgressPreference = $previousProgress }
}
if (-not (Test-Path -LiteralPath $mailpit)) { throw 'Mailpit executable was not found after extraction.' }
if ($PrepareOnly) {
    & $mailpit version --no-release-check
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host 'Local tools ready. Run .\start-local.ps1 to start the app and inbox.'
    exit $LASTEXITCODE
}

foreach ($localPort in @($Port,$InboxPort,$SmtpPort)) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$localPort)
    try { $listener.Start() }
    catch { throw "Port $localPort is already in use. Stop its existing service or choose another port." }
    finally { $listener.Stop() }
}
New-Item -ItemType Directory -Path $DataDirectory -Force | Out-Null
$dataPath = (Resolve-Path -LiteralPath $DataDirectory).Path.Replace('\','/')
$origin = "http://127.0.0.1:$Port"
$inbox = "http://127.0.0.1:$InboxPort"
$mailProcess = $null
$appExitCode = 1
try {
    # Do not inherit any Mailpit relay/forwarding settings from another project.
    $mailEnvironment = @{}
    Get-ChildItem Env: | Where-Object { $_.Name.StartsWith('MP_', [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object {
        $mailEnvironment[$_.Name] = $_.Value
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    try {
        $mailProcess = Start-Process -FilePath $mailpit -WindowStyle Hidden -PassThru -ArgumentList @(
            '--listen',"127.0.0.1:$InboxPort",'--smtp',"127.0.0.1:$SmtpPort",
            '--allowed-hosts','127.0.0.1,localhost','--disable-version-check','--smtp-disable-rdns',
            '--database',('"{0}/inbox.db"' -f $dataPath)
        )
    } finally {
        foreach ($name in $mailEnvironment.Keys) { Set-Item -LiteralPath "Env:$name" -Value $mailEnvironment[$name] }
    }
    $mailReady = $false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        if ($mailProcess.HasExited) { throw 'Mailpit exited during startup.' }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $inbox -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $mailReady=$true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $mailReady) { throw 'Local inbox did not become ready.' }
    Write-Host "App:   $origin"
    Write-Host "Inbox: $inbox"
    Write-Host "Data:  $dataPath"
    Write-Host 'Create an account using an address such as student@example.test. Verification/reset emails appear in the local inbox.'
    Write-Host 'Press Ctrl+C here to stop the app and inbox. Saved accounts, applications and captured messages remain.'
    $javaArguments = @(
        '-jar',$jar,'--spring.profiles.active=local',"--server.port=$Port",'--server.address=127.0.0.1',
        "--spring.datasource.url=jdbc:h2:file:$dataPath/campus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0",
        '--spring.datasource.username=sa','--spring.datasource.password=',
        '--spring.mail.host=127.0.0.1',"--spring.mail.port=$SmtpPort",
        '--spring.mail.username=','--spring.mail.password=',
        '--spring.mail.properties.mail.smtp.auth=false',
        '--spring.mail.properties.mail.smtp.starttls.enable=false','--spring.mail.properties.mail.smtp.starttls.required=false',
        '--campus.mail.production=false',"--campus.mail.public-origin=$origin", "--campus.mail.help-destination=$origin",
        '--campus.mail.sender=campus@localhost.test','--campus.mail.worker-enabled=true',
        '--campus.mail.key-id=local-capture-only','--campus.mail.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
        '--campus.reset.audit-secret=local-development-audit-secret-not-for-production','--campus.reset.audit-key-id=local-audit',
        '--campus.verification.rate-limit-secret=local-development-rate-limit-secret',
        '--campus.registration-enabled=true','--campus.reset.worker-enabled=true','--campus.reset.development-enabled=true',
        '--server.servlet.session.cookie.secure=false'
    )
    & $java @javaArguments
    $appExitCode = $LASTEXITCODE
} finally {
    if ($mailProcess -and -not $mailProcess.HasExited) { Stop-Process -Id $mailProcess.Id -ErrorAction SilentlyContinue }
}
exit $appExitCode
