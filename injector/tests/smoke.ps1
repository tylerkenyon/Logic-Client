param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixtureRoot = Join-Path $repoRoot 'build/injection-smoke'
$hostClasses = Join-Path $fixtureRoot 'host'
$payloadClasses = Join-Path $fixtureRoot 'payload'
$runtimeClasses = Join-Path $fixtureRoot 'runtime'
New-Item -ItemType Directory -Force $hostClasses, $payloadClasses, $runtimeClasses | Out-Null
$hostSources = @((Join-Path $PSScriptRoot 'fixture/FixtureHost.java'), (Join-Path $PSScriptRoot 'fixture/FixtureTarget.java'))
$hostSources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixture/net') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -d $hostClasses @hostSources
if ($LASTEXITCODE -ne 0) { throw 'Host compile failed' }
& javac -d $runtimeClasses (Join-Path $repoRoot 'src/main/java/dev/technix/logic/host/RuntimeHost.java')
if ($LASTEXITCODE -ne 0) { throw 'Runtime compile failed' }
& javac -cp $runtimeClasses -d $payloadClasses (Join-Path $PSScriptRoot 'fixture/dev/technix/logic/inject/Bootstrap.java') (Join-Path $repoRoot 'src/main/java/dev/technix/logic/inject/NativeBridge.java')
if ($LASTEXITCODE -ne 0) { throw 'Payload compile failed' }
$payloadJar = Join-Path $fixtureRoot 'smoke.jar'
& jar --create --file $payloadJar -C $payloadClasses .
if ($LASTEXITCODE -ne 0) { throw 'Payload packaging failed' }
$previousPayload = $env:LOGIC_PAYLOAD
$previousRuntime = $env:LOGIC_RUNTIME
try {
    $env:LOGIC_PAYLOAD = $payloadJar
    $env:LOGIC_RUNTIME = Join-Path $fixtureRoot 'smoke-runtime.jar'
    & jar --create --file $env:LOGIC_RUNTIME -C $runtimeClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Runtime packaging failed' }
    & cargo build --manifest-path (Join-Path $repoRoot 'injector/Cargo.toml') --release --target-dir (Join-Path $fixtureRoot 'rust')
    if ($LASTEXITCODE -ne 0) { throw 'Native smoke build failed' }
    $javaPath = (Get-Command java).Source
    $fixtureProcess = Start-Process -FilePath $javaPath -ArgumentList @('-cp', ('"' + $hostClasses + '"'), 'FixtureHost') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $fixtureRoot 'host.out') -RedirectStandardError (Join-Path $fixtureRoot 'host.err')
    try {
        for ($cycle = 1; $cycle -le 2; $cycle++) {
            & (Join-Path $fixtureRoot 'rust/release/LogicLauncher.exe') $fixtureProcess.Id (Join-Path $fixtureRoot 'rust/release/logic_native.dll')
            if ($LASTEXITCODE -ne 0) { throw "Native injection cycle $cycle failed" }
            $cycleDeadline = [DateTime]::UtcNow.AddSeconds(12)
            do {
                if ($fixtureProcess.HasExited -and $cycle -eq 1) { throw 'Fixture exited before reinjection' }
                $cycleFinished = (Get-Content -LiteralPath (Join-Path $fixtureRoot 'host.out') -Raw) -match "Fixture cycle $cycle released"
                if (!$cycleFinished) { Start-Sleep -Milliseconds 100 }
            } while (!$cycleFinished -and [DateTime]::UtcNow -lt $cycleDeadline)
            if (!$cycleFinished) { throw "Native release cycle $cycle timed out" }
        }
        if (!$fixtureProcess.WaitForExit(10000) -or $fixtureProcess.ExitCode -ne 0) { throw 'Fixture did not exit successfully' }
        Get-Content -LiteralPath (Join-Path $fixtureRoot 'host.out')
    } finally {
        if (!$fixtureProcess.HasExited) { Stop-Process -Id $fixtureProcess.Id }
    }
} finally {
    $env:LOGIC_PAYLOAD = $previousPayload
    $env:LOGIC_RUNTIME = $previousRuntime
}
