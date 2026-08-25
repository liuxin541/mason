@echo off
cd /d %~dp0
echo ========================================
echo   小房间模式 (20x20x24, 3层)
echo   用于观察烟和火焰模型
echo ========================================
java -Dfireescape.voxel=./nonexistent.voxel -cp "mason\target\classes" sim.app.fireescape.FireEscapeHttpServer --no-browser
pause
