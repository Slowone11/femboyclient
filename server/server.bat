@echo off
rem Starts the Femboy Client website. One file, four ways to run it:
rem   server.bat             this PC only (http://127.0.0.1:8000). Same as double-clicking.
rem   server.bat lan         other PCs on your network can reach it too. Only use this at home.
rem   server.bat forever     like lan, and restarts it automatically if it ever exits (logs in server.log).
rem   server.bat autostart   run ONCE: Windows then runs "server.bat forever" (minimized) at every login.
cd /d "%~dp0"
if /i "%~1"=="lan" goto lan
if /i "%~1"=="forever" goto forever
if /i "%~1"=="autostart" goto autostart

.venv\Scripts\python.exe -m uvicorn app:app --host 127.0.0.1 --port 8000
goto :eof

:lan
.venv\Scripts\python.exe -m uvicorn app:app --host 0.0.0.0 --port 8000
goto :eof

:forever
echo [%date% %time%] watchdog: starting server >> server.log
.venv\Scripts\python.exe -m uvicorn app:app --host 0.0.0.0 --port 8000 --no-access-log >> server.log 2>> server.err.log
echo [%date% %time%] watchdog: server exited with code %errorlevel%, restarting in 5s >> server.log
ping -n 6 127.0.0.1 >nul
goto forever

:autostart
schtasks /Create /TN "Femboy Client Server" /TR "cmd /c start \"\" /min \"%~f0\" forever" /SC ONLOGON /F
if %errorlevel%==0 (echo Done. The server will now start at every login.) else (echo Failed - try right-click, Run as administrator.)
pause
