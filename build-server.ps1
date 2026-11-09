param([switch]$MariaDbTests)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Java/jdk-26.0.1' }
    if (-not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/javac.exe")) { throw 'Укажите JAVA_HOME на JDK 25 или 26 (JRE недостаточно).' }
    $manifest = Get-Content -LiteralPath 'deps/manifest.json' -Raw | ConvertFrom-Json
    foreach ($dependency in $manifest) {
        $file = Join-Path (Split-Path $PSScriptRoot -Parent) $dependency.path
        if (-not (Test-Path -LiteralPath $file)) { throw "Нет зависимости: $file" }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dependency.sha256) { throw "Изменилась зависимость: $file" }
    }
    $argsForMaven = @('-f','pom-server.xml','clean','verify')
    if ($MariaDbTests) { $argsForMaven += '-Dae.mariadb.tests=true' }
    & ./mvnw.cmd @argsForMaven
    if ($LASTEXITCODE -ne 0) { throw "Сборка завершилась с кодом $LASTEXITCODE" }
    Get-FileHash -LiteralPath 'target/AzerothEconomy-1.0.0.jar' -Algorithm SHA256
} finally { Pop-Location }
