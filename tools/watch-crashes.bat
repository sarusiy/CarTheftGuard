@echo off
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0watch-crashes.ps1" %*
