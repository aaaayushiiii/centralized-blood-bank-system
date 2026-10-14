Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "Starting Blood Bank JDBC Backend Server..." -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Cyan

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$ojdbc = "C:\Users\ankit\.m2\repository\com\oracle\database\jdbc\ojdbc8\19.8.0.0\ojdbc8-19.8.0.0.jar"
$jackson1 = "C:\Users\ankit\.m2\repository\com\fasterxml\jackson\core\jackson-databind\2.15.2\jackson-databind-2.15.2.jar"
$jackson2 = "C:\Users\ankit\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.15.2\jackson-core-2.15.2.jar"
$jackson3 = "C:\Users\ankit\.m2\repository\com\fasterxml\jackson\core\jackson-annotations\2.15.2\jackson-annotations-2.15.2.jar"
$cp = "target/classes;$ojdbc;$jackson1;$jackson2;$jackson3"

java -cp $cp com.bloodbank.server.BloodBankServer
