@echo off
cd /d %~dp0
java -cp "target\classes" sim.app.fireescape.FireEscapeHttpServer --test --no-browser
pause
