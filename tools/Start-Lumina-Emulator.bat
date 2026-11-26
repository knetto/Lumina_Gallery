@echo off
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-emulator.ps1" -SkipPhotos -NoBuild
if errorlevel 1 pause
