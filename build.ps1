param([switch]$MariaDbTests)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $cache = Join-Path $PSScriptRoot '.build-deps'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    $dependency = Join-Path $cache 'PlaceholderAPI-2.12.3.jar'
    $expected = (Get-Content -LiteralPath 'deps/placeholderapi.sha256' -Raw).Trim()
    if (-not (Test-Path -LiteralPath $dependency)) {
        Invoke-WebRequest -Uri 'https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar' -OutFile "$dependency.download"
        if ((Get-FileHash -LiteralPath "$dependency.download" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'SHA-256 PlaceholderAPI не совпал.' }
        Move-Item -LiteralPath "$dependency.download" -Destination $dependency
    }
    if ((Get-FileHash -LiteralPath $dependency -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Изменён PlaceholderAPI JAR.' }
    $mavenArgs = @('clean','verify')
    if ($MariaDbTests) { $mavenArgs += '-Dae.mariadb.tests=true' }
    # Windows PowerShell 5.1 treats any stderr line of a native tool (e.g. JDK warnings) as an error
    # under 'Stop'; the real result is the exit code checked below.
    $ErrorActionPreference = 'Continue'
    & ./mvnw.cmd @mavenArgs
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Сборка завершилась с кодом $LASTEXITCODE" }
    Get-FileHash -LiteralPath 'target/TerraWoWmain-1.1.0.jar' -Algorithm SHA256
} finally { Pop-Location }
