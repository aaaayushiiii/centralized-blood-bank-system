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
    $env:CORS_ORIGINS = "http://127.0.0.1:5173,http://localhost:5173"
    $env:COOKIE_SECURE = "false"
    $root = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-web-admin-auth-" + [guid]::NewGuid().ToString("N"))
    $main = Join-Path $root "main"
    $setup = Join-Path $root "setup"
    $tests = Join-Path $root "test"
    New-Item -ItemType Directory -Force $main, $setup, $tests | Out-Null
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $main @sources
    if ($LASTEXITCODE -ne 0) { throw "Production compilation failed." }
    & $javac -encoding UTF-8 --release 17 -cp $main -d $setup tools/src/main/java/com/bloodbank/tools/CreateWebAdmin.java
    if ($LASTEXITCODE -ne 0) { throw "Offline setup compilation failed." }
    & $javac -encoding UTF-8 --release 17 -cp "$main;$setup" -d $tests `
        src/test/java/oracle/jdbc/OracleDriver.java `
        src/test/java/com/bloodbank/WebAdminAuthenticationTests.java `
        tools/src/test/java/com/bloodbank/tools/CreateWebAdminTests.java
    if ($LASTEXITCODE -ne 0) { throw "Focused test compilation failed." }
    & $java -cp "$main;$setup;$tests" com.bloodbank.tools.CreateWebAdminTests
    if ($LASTEXITCODE -ne 0) { throw "Offline setup tests failed." }
    $env:WEB_ADMIN_ENABLED = $null
    $env:PORT = "18091"
    & $java -cp "$main;$tests" com.bloodbank.WebAdminAuthenticationTests disabled
    if ($LASTEXITCODE -ne 0) { throw "Default-disabled authentication tests failed." }
    $env:WEB_ADMIN_ENABLED = "true"
    $env:PORT = "18092"
    & $java -cp "$main;$tests" com.bloodbank.WebAdminAuthenticationTests enabled
    if ($LASTEXITCODE -ne 0) { throw "Enabled administrator authentication tests failed." }
    # The web classpath must never contain the offline bootstrap or restricted CLI.
    foreach ($class in @("com.bloodbank.tools.CreateWebAdmin", "com.bloodbank.admin.AdminCli")) {
        try {
            $ErrorActionPreference = "Continue"
            $output = & $java -cp $main $class 2>&1
            $isolationExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = "Stop" }
        if ($isolationExit -eq 0 -or ($output -join "`n") -notmatch 'ClassNotFoundException') { throw "Offline class leaked onto web classpath." }
    }
    Write-Output "PASS: separate Java 17 production/setup compilation and offline-class isolation."
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
    Pop-Location
}
