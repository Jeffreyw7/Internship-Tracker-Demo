---
name: verify-campus-tracker
description: Verify Campus Internship Tracker in Chrome against its bundled Spring Boot app on Windows. Use for local browser evidence of application entry, CSV import and recovery, and password reset.
---

# Verify Campus Internship Tracker

Use the repository’s Chrome runner from the checkout you want to verify. Choose a journey from the [feature map](features/README.md). The runner executes its full scenario in one invocation.

## Prepare the bundle

Use PowerShell from the repository root with Git, Node 22.18 or newer, JDK 17 or newer, and installed Google Chrome. Reuse a bundled JAR built from the source under review, or build one in a fresh checkout. The Maven `with-ui` profile consumes the existing `dist` directory.

```powershell
npm.cmd ci --include=dev
if ($LASTEXITCODE) { throw 'Frontend dependency installation failed' }
npm.cmd run build
if ($LASTEXITCODE) { throw 'Frontend build failed' }
Push-Location backend
try {
    .\mvnw.cmd -B -ntp -Pwith-ui '-DskipTests' package
    if ($LASTEXITCODE) { throw 'Bundled JAR build failed' }
} finally { Pop-Location }
```

If the Windows wrapper fails before Maven starts, use its cached Maven 3.9.11 distribution. Start this fallback from the repository root. This fallback built the trial JAR. If you use an existing dependency cache, add `-o` and `"-Dmaven.repo.local=$cache"` with `$cache` set to its path. Packaging skips backend tests. [Build instructions](../../../docs/CI.md)

```powershell
$dist = Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.11'
$mvn = (Get-ChildItem -LiteralPath $dist -Filter mvn.cmd -File -Recurse | Select-Object -First 1).FullName
Push-Location backend
try {
    & $mvn -B -ntp -Pwith-ui '-DskipTests' package
    if ($LASTEXITCODE) { throw 'Bundled JAR build failed' }
} finally { Pop-Location }
```

The runner loads `playwright-core@1.65.1` and `axe-core@4.14.1`. For a fresh tools directory, run the following from the repository root. An existing installation of those versions can be selected with `BROWSER_VALIDATION_TOOLS`. Set `BROWSER_VALIDATION_CHROME` only if Chrome needs an explicit executable path. [Browser setup](../../../docs/BROWSER-VALIDATION.md)

```powershell
npm.cmd install --prefix test-results/browser-tools --save-exact --no-audit --no-fund playwright-core@1.65.1 axe-core@4.14.1
```

## Doctor and launch

Use one PowerShell session for the following blocks. Check the revision and expected JAR before the first drive. `BROWSER_VALIDATION_JAR` can point to a bundle built in another checkout of the same source.

```powershell
$ErrorActionPreference = 'Stop'
$repo = (Get-Location).Path
if (!$env:BROWSER_VALIDATION_JAR) { $env:BROWSER_VALIDATION_JAR = Join-Path $repo 'backend/target/campus-tracker-2.0.0.jar' }
if (!$env:BROWSER_VALIDATION_TOOLS) { $env:BROWSER_VALIDATION_TOOLS = Join-Path $repo 'test-results/browser-tools' }
$revision = git rev-parse HEAD
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $env:BROWSER_VALIDATION_JAR).Hash
foreach ($name in 'playwright-core','axe-core') {
    $p = Get-Content -Raw -LiteralPath (Join-Path $env:BROWSER_VALIDATION_TOOLS "node_modules/$name/package.json") | ConvertFrom-Json
    Write-Output "$($p.name) $($p.version)"
}
git status --short
```

The runner starts Java on its own loopback port, migrates fresh H2 data with Flyway, and captures mail on a loopback SMTP listener. It waits for the bundled homepage, checks the **Welcome back.** screen, registers and verifies synthetic accounts, and checks the account name and **Saved to your account** before editing records. The mapped drives use those browser sessions. [Runner and assertions](../../../tests/browser_validation.mjs)

```powershell
$runId = [guid]::NewGuid().ToString('N').Substring(0,12)
$out = Join-Path $repo "test-results/verify-campus-tracker/$runId"
$scratch = Join-Path ([IO.Path]::GetTempPath()) "ct-browser-$runId"
New-Item -ItemType Directory -Path $out,$scratch | Out-Null
$env:BROWSER_VALIDATION_OUTPUT = $out
Write-Output $out
$priorTemp = $env:TEMP
$priorTmp = $env:TMP
try {
    $env:TEMP = $scratch
    $env:TMP = $scratch
    node tests/browser_validation.mjs
    $runnerExit = $LASTEXITCODE
} finally {
    $env:TEMP = $priorTemp
    $env:TMP = $priorTmp
}
```

For a live read-only doctor, use the printed run directory in another PowerShell. In its `server.log`, find **Starting CampusApplication** with the expected JAR path and Java PID, **Tomcat started on port**, and **Started CampusApplication**. While that JVM is live, the following anonymous request should return `401`. The authenticated doctor is the runner’s account-name and saved-workspace check. Repeat these checks after an unexpected result before retrying.

```powershell
$out = Read-Host 'Run directory printed by the driver'
$bootLog = Join-Path $out 'server.log'
Select-String -LiteralPath $bootLog -Pattern 'Starting CampusApplication','Tomcat started on port','Started CampusApplication'
$bound = Select-String -LiteralPath $bootLog -Pattern 'Tomcat started on port (\d+)' | Select-Object -Last 1
if ($bound) {
    $port = $bound.Matches[0].Groups[1].Value
    curl.exe --noproxy '*' -sS -o NUL -w '%{http_code}' "http://127.0.0.1:$port/api/auth/me"
}
```

## Evidence and cleanup

Back in the launch session, match the selected journey to its named check in `results.json`. The report includes the JAR SHA-256, revision, working tree, Chrome version, and axe findings. `workspace-320.png` shows the final narrow viewport. `server.log` carries startup diagnostics. Keep tokens, cookies, and captured mail out of shared reports.

```powershell
$result = Get-Content -Raw -LiteralPath (Join-Path $out 'results.json') | ConvertFrom-Json
if ($result.revision -ne $revision -or $result.jarSha256 -ne $hash) { throw 'Build identity changed' }
$result.checks | Select-Object name,result
Get-ChildItem -LiteralPath $out -File | Select-Object Name,Length
```

The runner closes Chrome and SMTP, stops its Java child, and removes its `campus-browser-*` database directory. Windows can leave Tomcat and JVM scratch files. After the runner exits, verify that the recorded Java PID is gone and remove only the temp directory created above. On interruption or a missing report, keep the run’s output and confirm child ownership and exit from `server.log` before cleaning scratch.

```powershell
$started = Select-String -LiteralPath (Join-Path $out 'server.log') -Pattern 'with PID (\d+)' | Select-Object -First 1
if (!$started) { throw 'Check the startup log before cleanup' }
$javaId = [int]$started.Matches[0].Groups[1].Value
if (Get-Process -Id $javaId -ErrorAction SilentlyContinue) { throw 'Check the recorded Java PID before cleanup' }
$expectedScratch = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) "ct-browser-$runId"))
$owned = Get-Item -LiteralPath $scratch
if ($owned.FullName -ne $expectedScratch -or $owned.Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected scratch path' }
Remove-Item -LiteralPath $owned.FullName -Recurse -Force
Get-Item -LiteralPath (Join-Path $out 'results.json')
if ($runnerExit -ne 0 -or !$result.checks.Count -or @($result.checks | Where-Object result -ne 'pass').Count) { throw 'Inspect the run results and server log' }
```

Open the retained screenshot when the scenario reached its final viewport. For a retry, repeat the launch block to allocate a fresh run directory.

Local Chrome verifies the bundled app. Public ingress timing for preview and confirmation requires receipts from the deployed Worker. [Hosted evidence requirements](../../../docs/PASSWORD-RESET-READINESS.md)
