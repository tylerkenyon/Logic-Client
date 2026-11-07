$ErrorActionPreference = 'Stop'
$directory = Join-Path $PSScriptRoot '..\dist-release'
$meta = Get-Content -LiteralPath (Join-Path $directory 'meta.json') -Raw | ConvertFrom-Json
if ($meta.version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') { throw 'Invalid release version' }
$archive = Join-Path $directory "Logic-Client-v$($meta.version)-windows.zip"
$names = @('LogicLauncher.exe', 'logic_native.dll', 'meta.json', 'README.md', 'UPSTREAM.md', 'CONFIGURATION.md')
$files = $names | ForEach-Object { Join-Path $directory $_ }
foreach ($file in $files) { if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing release file: $file" } }
Compress-Archive -LiteralPath $files -DestinationPath $archive -Force
Write-Output "Created $archive"
