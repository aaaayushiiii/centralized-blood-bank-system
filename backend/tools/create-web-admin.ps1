param([string]$OracleJdbcJar = $env:OJDBC_JAR)
$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
try {
    if ($env:WEB_ADMIN_ENABLED -ine "true") { throw "Explicit WEB_ADMIN_ENABLED=true is required for intentional local setup." }
    if ($env:ORACLE_USER -cne "SYSTEM" -or $env:ORACLE_URL -cnotmatch '^jdbc:oracle:thin:@//(localhost|127\.0\.0\.1|\[::1\]):[0-9]{1,5}/XEPDB1$') { throw "Setup is restricted to the existing local SYSTEM/XEPDB1 database." }
    if (-not $OracleJdbcJar -or -not (Test-Path -LiteralPath $OracleJdbcJar -PathType Leaf)) { throw "Provide the existing trusted Oracle JDBC jar." }
    $driver = (Resolve-Path -LiteralPath $OracleJdbcJar).Path
    $classes = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-web-admin-setup-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $classes | Out-Null
    $sources = @(
        "../src/main/java/com/bloodbank/util/ApiException.java",
        "../src/main/java/com/bloodbank/util/Data.java",
        "../src/main/java/com/bloodbank/util/Passwords.java",
        "../src/main/java/com/bloodbank/config/WebAdminPolicy.java",
        "src/main/java/com/bloodbank/tools/CreateWebAdmin.java"
    )
    & $javac -encoding UTF-8 --release 17 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw "Offline administrator setup compilation failed." }
    & $java -cp "$classes;$driver" com.bloodbank.tools.CreateWebAdmin --create-local-web-admin
    if ($LASTEXITCODE -ne 0) { throw "Setup failed/refused; do not retry without checking the outcome." }
} finally { Pop-Location }
