@echo off
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0build-install-run.ps1" %*
