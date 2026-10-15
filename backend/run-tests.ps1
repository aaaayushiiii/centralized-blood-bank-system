$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
$saved = @{}
foreach ($key in @("ORACLE_URL", "ORACLE_USER", "ORACLE_PASSWORD", "PORT", "BIND_ADDRESS", "CORS_ORIGINS", "COOKIE_SECURE")) { $saved[$key] = [Environment]::GetEnvironmentVariable($key) }
try {
    $env:PORT = "18089"
    $env:BIND_ADDRESS = "127.0.0.1"
    $env:CORS_ORIGINS = "http://127.0.0.1:5173,http://localhost:5173"
    $env:COOKIE_SECURE = "false"
    $env:ORACLE_URL = "jdbc:oracle:thin:@TEST-ONLY"
    $env:ORACLE_USER = "test-only"
    $env:ORACLE_PASSWORD = [guid]::NewGuid().ToString()
    $testClasses = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-tests-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $testClasses | Out-Null
    $main = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    $tests = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $testClasses @main @tests
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }
    & $java -cp "$testClasses" com.bloodbank.RegressionTests
    if ($LASTEXITCODE -ne 0) { throw "Regression tests failed" }
    & $java -cp "$testClasses" com.bloodbank.HttpRegressionTests
    if ($LASTEXITCODE -ne 0) { throw "HTTP tests failed" }
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
    Pop-Location
}
