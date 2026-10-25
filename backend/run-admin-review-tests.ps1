$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
$saved = @{}
foreach ($key in @("ORACLE_URL", "ORACLE_USER", "ORACLE_PASSWORD", "PORT", "BIND_ADDRESS", "CORS_ORIGINS", "COOKIE_SECURE", "WEB_ADMIN_ENABLED")) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key)
}
try {
    $env:ORACLE_URL = "jdbc:oracle:thin:@TEST-ONLY"
    $env:ORACLE_USER = "test-only"
    $env:ORACLE_PASSWORD = [guid]::NewGuid().ToString()
    $env:BIND_ADDRESS = "127.0.0.1"
    # Include a synthetic external allowed CORS origin to test the stricter admin-local guard.
    $env:CORS_ORIGINS = "http://127.0.0.1:5173,http://localhost:5173,http://example.test"
    $env:COOKIE_SECURE = "false"
    $root = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-admin-review-" + [guid]::NewGuid().ToString("N"))
    $main = Join-Path $root "main"
    $tests = Join-Path $root "tests"
    New-Item -ItemType Directory -Force $main, $tests | Out-Null
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $main @sources
    if ($LASTEXITCODE -ne 0) { throw "Production compilation failed." }
    & $javac -encoding UTF-8 --release 17 -cp $main -d $tests `
        src/test/java/oracle/jdbc/OracleDriver.java src/test/java/com/bloodbank/AdminHospitalRegistrationTests.java
    if ($LASTEXITCODE -ne 0) { throw "Administrator review test compilation failed." }
    $env:WEB_ADMIN_ENABLED = $null
    $env:PORT = "18093"
    & $java -cp "$main;$tests" com.bloodbank.AdminHospitalRegistrationTests disabled
    if ($LASTEXITCODE -ne 0) { throw "Default-disabled review endpoint tests failed." }
    $env:WEB_ADMIN_ENABLED = "true"
    $env:PORT = "18094"
    & $java -cp "$main;$tests" com.bloodbank.AdminHospitalRegistrationTests enabled
    if ($LASTEXITCODE -ne 0) { throw "Administrator review tests failed." }
    Write-Output "PASS: Java 17 production/test compilation; no live JDBC driver, account creation or old functional suites."
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
    Pop-Location
}
