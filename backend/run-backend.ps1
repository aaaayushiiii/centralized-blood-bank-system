param([string]$OracleJdbcJar = $env:OJDBC_JAR)
$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
try {
    if (-not $OracleJdbcJar -or -not (Test-Path -LiteralPath $OracleJdbcJar -PathType Leaf)) {
        throw "Set OJDBC_JAR to the absolute path of your Oracle JDBC jar."
    }
    foreach ($key in @("ORACLE_URL", "ORACLE_USER", "ORACLE_PASSWORD")) {
        if (-not [Environment]::GetEnvironmentVariable($key)) { throw "Missing $key" }
    }
    $driver = (Resolve-Path -LiteralPath $OracleJdbcJar).Path
    $classes = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-java-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $classes | Out-Null
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw "Java compilation failed" }
    & $java -cp "$classes;$driver" com.bloodbank.server.BloodBankServer
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
} finally { Pop-Location }
