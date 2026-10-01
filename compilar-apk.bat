@echo off
rem Doble clic para compilar el APK. Llama al script de PowerShell sin pedir cambiar la politica de ejecucion.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0compilar-apk.ps1"
pause
