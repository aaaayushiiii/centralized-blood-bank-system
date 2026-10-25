param(
    [Parameter(Mandatory = $true)][ValidateSet("list", "view", "approve", "reject")][string]$Action,
    [string]$RequestId,
    [string]$OracleJdbcJar = $env:OJDBC_JAR
)
$ErrorActionPreference = "Stop"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/javac.exe" } else { "javac" }
Push-Location $PSScriptRoot
try {
    if (-not $OracleJdbcJar -or -not (Test-Path -LiteralPath $OracleJdbcJar -PathType Leaf)) { throw "Provide the Oracle JDBC jar path." }
    if (-not $env:ADMIN_ORACLE_URL -or -not $env:ADMIN_ORACLE_USER) { throw "Set separate ADMIN_ORACLE_URL and ADMIN_ORACLE_USER; public-server credentials are not used." }
    if ($Action -ne "list" -and -not $RequestId) { throw "RequestId required." }
    if ($RequestId -and $RequestId -notmatch '^[1-9][0-9]{0,9}$') { throw "RequestId must be a positive numeric ID." }
    $driver = (Resolve-Path -LiteralPath $OracleJdbcJar).Path
    $classes = Join-Path ([IO.Path]::GetTempPath()) ("bloodbank-admin-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $classes | Out-Null
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 17 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw "Admin compilation failed." }
    $cliArguments = @($Action)
    if ($RequestId) { $cliArguments += $RequestId }
    & $java -cp "$classes;$driver" com.bloodbank.admin.AdminCli @cliArguments
    if ($LASTEXITCODE -ne 0) { throw "Admin tool failed/refused the operation. Inspect application status before retrying." }
} finally { Pop-Location }
