$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
$saved = @{}
foreach ($key in @("ORACLE_URL", "ORACLE_USER", "ORACLE_PASSWORD", "PORT", "BIND_ADDRESS", "CORS_ORIGINS", "COOKIE_SECURE")) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key)
}
try {
    # Only the test-only JDBC double is on this test runtime's classpath.
    $env:ORACLE_URL = "jdbc:oracle:thin:@TEST-ONLY"
    $env:ORACLE_USER = "test-only"
    $env:ORACLE_PASSWORD = [guid]::NewGuid().ToString()
    $env:PORT = "18090"
    $env:BIND_ADDRESS = "127.0.0.1"
    $env:CORS_ORIGINS = "http://127.0.0.1:5173,http://localhost:5173"
    $env:COOKIE_SECURE = "false"
    $buildRoot = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-onboarding-" + [guid]::NewGuid().ToString("N"))
    $productionClasses = Join-Path $buildRoot "main"
    $testClasses = Join-Path $buildRoot "test"
    New-Item -ItemType Directory -Force $productionClasses, $testClasses | Out-Null
    $main = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $productionClasses @main
    if ($LASTEXITCODE -ne 0) { throw "Production compilation failed" }
    Write-Output "PASS: Java 17 production compilation (no test driver)."
    & $javac -encoding UTF-8 --release 17 -cp $productionClasses -d $testClasses `
        src/test/java/oracle/jdbc/OracleDriver.java src/test/java/com/bloodbank/HospitalRegistrationTests.java
    if ($LASTEXITCODE -ne 0) { throw "Onboarding test compilation failed" }
    & $java -cp "$productionClasses;$testClasses" com.bloodbank.HospitalRegistrationTests
    if ($LASTEXITCODE -ne 0) { throw "Onboarding tests failed" }
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
    Pop-Location
}
