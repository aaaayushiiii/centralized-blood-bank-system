@echo off
powershell.exe -NoProfile -File "%~dp0run-backend.ps1" %*
exit /b %ERRORLEVEL%
