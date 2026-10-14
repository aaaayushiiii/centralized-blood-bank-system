@echo off
echo ========================================================
echo Starting Blood Bank JDBC Backend Server...
echo ========================================================
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "OJDBC=C:\Users\ankit\.m2\repository\com\oracle\database\jdbc\ojdbc8\19.8.0.0\ojdbc8-19.8.0.0.jar"
set "JACKSON1=C:\Users\ankit\.m2\repository\com\fasterxml\jackson\core\jackson-databind\2.15.2\jackson-databind-2.15.2.jar"
set "JACKSON2=C:\Users\ankit\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.15.2\jackson-core-2.15.2.jar"
set "JACKSON3=C:\Users\ankit\.m2\repository\com\fasterxml\jackson\core\jackson-annotations\2.15.2\jackson-annotations-2.15.2.jar"
set "CP=target\classes;%OJDBC%;%JACKSON1%;%JACKSON2%;%JACKSON3%"

java -cp "%CP%" com.bloodbank.server.BloodBankServer
pause
