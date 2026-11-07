param([switch]$Offline, [switch]$SmokeTest, [string]$OutputDirectory = 'dist')
$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
Push-Location $repoRoot
$previousPayload = $env:LOGIC_PAYLOAD
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $javaHomes = @($env:JAVA_HOME)
    $javaHomes += @(Get-Command javac.exe -All -ErrorAction SilentlyContinue | ForEach-Object {
        Split-Path -Parent (Split-Path -Parent $_.Source)
    })
    $jdkRoots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft", "$env:USERPROFILE\.jdks")
    $javaHomes += @(Get-ChildItem -LiteralPath $jdkRoots -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    $buildJdk = $null
    foreach ($candidate in ($javaHomes | Where-Object { $_ } | Select-Object -Unique)) {
        $releaseFile = Join-Path $candidate 'release'
        if (!(Test-Path -LiteralPath $releaseFile)) { continue }
        if ((Get-Content -LiteralPath $releaseFile) -match '^JAVA_VERSION="25(?:[.+-]|")' -and
                (Test-Path -LiteralPath (Join-Path $candidate 'bin/java.exe')) -and
                (Test-Path -LiteralPath (Join-Path $candidate 'bin/javac.exe')) -and
                (Test-Path -LiteralPath (Join-Path $candidate 'bin/jar.exe'))) {
            $buildJdk = (Resolve-Path -LiteralPath $candidate).Path
            break
        }
    }
    if (!$buildJdk) { throw 'JDK 25 was not found. Install JDK 25 or set JAVA_HOME to its installation directory.' }
    $env:JAVA_HOME = $buildJdk
    $env:PATH = (Join-Path $buildJdk 'bin') + [IO.Path]::PathSeparator + $previousPath
    Write-Output "Building with JDK 25 at $buildJdk"
    foreach ($tool in @('java', 'javac', 'jar', 'python', 'cargo', 'rustc')) {
        if (!(Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Missing build tool: $tool. Install JDK 25, Python 3, Rust, and MSVC C++ build tools."
        }
    }
    & python -c "import sys; assert sys.version_info >= (3, 9), 'Python 3.9 or newer is required'"
    if ($LASTEXITCODE -ne 0) { throw 'Unsupported Python version' }
    $gradleOptions = @()
    if ($Offline) { $gradleOptions += '--offline' }
    & ./gradlew.bat -p mica build @gradleOptions
    if ($LASTEXITCODE -ne 0) { throw 'Mica build failed' }
    Copy-Item -LiteralPath 'mica/build/libs/mica-0.2.jar' -Destination 'libs/mica-0.2.jar'
    & ./gradlew.bat build @gradleOptions
    if ($LASTEXITCODE -ne 0) { throw 'Logic build failed' }
    $version = ((Get-Content gradle.properties | Where-Object { $_ -match '^version=' }) -split '=',2)[1]
    $mod = "build/libs/logic-$version.jar"
    & python scripts/package-injection.py $mod build/injection/logic-payload.jar
    if ($LASTEXITCODE -ne 0) { throw 'Payload packaging failed' }
    $env:LOGIC_PAYLOAD = (Resolve-Path 'build/injection/logic-payload.jar').Path
    & cargo test --manifest-path injector/Cargo.toml
    if ($LASTEXITCODE -ne 0) { throw 'Rust tests failed' }
    & cargo build --manifest-path injector/Cargo.toml --release
    if ($LASTEXITCODE -ne 0) { throw 'Rust build failed' }
    if ($SmokeTest) {
        & ./injector/tests/smoke.ps1
        if (!$?) { throw 'Native JVM smoke test failed' }
    }
    New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
    Copy-Item -LiteralPath 'injector/target/release/LogicLauncher.exe','injector/target/release/logic_native.dll' -Destination $OutputDirectory
    Copy-Item -LiteralPath $mod,'libs/mica-0.2.jar','injector/README.md','injector/UPSTREAM.md' -Destination $OutputDirectory
    Copy-Item -LiteralPath 'build/injection/logic-payload.jar' -Destination (Join-Path $OutputDirectory 'logic-payload.jar')
    Copy-Item -LiteralPath 'build/injection/logic-runtime.jar' -Destination (Join-Path $OutputDirectory 'logic-runtime.jar')
    Copy-Item -LiteralPath 'docs/configuration.md' -Destination (Join-Path $OutputDirectory 'CONFIGURATION.md')
    $updateFiles = @{}
    foreach ($name in @('LogicLauncher.exe', 'logic_native.dll')) {
        $updateFiles[$name] = (Get-FileHash -LiteralPath (Join-Path $OutputDirectory $name) -Algorithm SHA256).Hash.ToLower()
    }
    $metadata = @{ schema = 1; repository = 'tylerkenyon/Logic-Client'; version = $version; files = $updateFiles } | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText((Join-Path (Resolve-Path $OutputDirectory) 'meta.json'), $metadata, [Text.UTF8Encoding]::new($false))
    Get-ChildItem -LiteralPath $OutputDirectory -File | Where-Object { $_.Extension -in '.exe', '.dll', '.jar' } |
        ForEach-Object { $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256; "$($hash.Hash.ToLower())  $($_.Name)" } |
        Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt')
    Write-Output "Built launcher, native loader and mod jars in $OutputDirectory."
} finally {
    $env:LOGIC_PAYLOAD = $previousPayload
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    Pop-Location
}
