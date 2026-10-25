$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
$savedUrl = $env:ORACLE_URL
try {
    $env:ORACLE_URL = "jdbc:oracle:thin:@TEST-ONLY"
    $root = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-admin-tests-" + [guid]::NewGuid().ToString("N"))
    $web = Join-Path $root "web"
    $admin = Join-Path $root "admin"
    $tests = Join-Path $root "tests"
    New-Item -ItemType Directory -Force $web, $admin, $tests | Out-Null
    $webSources = @(Get-ChildItem ../src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $web @webSources
    if ($LASTEXITCODE -ne 0) { throw "Public backend compilation failed." }
    if (Test-Path (Join-Path $web "com/bloodbank/admin")) { throw "Admin classes leaked into public backend." }
    $adminSources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $admin @adminSources
    if ($LASTEXITCODE -ne 0) { throw "Isolated administrator compilation failed." }
    & $javac -encoding UTF-8 --release 17 -cp $admin -d $tests `
        ../src/test/java/oracle/jdbc/OracleDriver.java src/test/java/com/bloodbank/admin/AdminTests.java
    if ($LASTEXITCODE -ne 0) { throw "Admin test compilation failed." }
    & $java -cp "$admin;$tests" com.bloodbank.admin.AdminTests
    if ($LASTEXITCODE -ne 0) { throw "Admin tests failed." }
    # Classpath isolation is tested at runtime as well as by inspecting compiled files.
    try {
        # Windows PowerShell treats native stderr as an error record; failure is expected here.
        $ErrorActionPreference = "Continue"
        $isolationOutput = & $java -cp $web com.bloodbank.admin.AdminCli list 2>&1
        $isolationExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = "Stop" }
    if ($isolationExit -eq 0 -or ($isolationOutput -join "`n") -notmatch 'ClassNotFoundException') { throw "Public classpath isolation failed." }
    Write-Output "PASS: separate Java 17 web/admin compilation; administrator class absent from public runtime."
} finally {
    $env:ORACLE_URL = $savedUrl
    Pop-Location
}
