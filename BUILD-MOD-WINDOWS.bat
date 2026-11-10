@echo off
title Building AI Companion mod...
echo.
echo  Building the AI Companion mod for Minecraft 26.3
echo  (needs Java 25 - get it free at https://adoptium.net/temurin/releases/?version=25)
echo.
java -version >nul 2>&1
if errorlevel 1 (
  echo  Java was not found. Install Java 25 from the link above, then run this again.
  pause
  exit /b 1
)
call gradlew.bat build --no-daemon
if errorlevel 1 (
  echo.
  echo  The build failed. Scroll up to see the error.
  pause
  exit /b 1
)
if not exist "READY-TO-USE" mkdir "READY-TO-USE"
for %%f in (build\libs\ai-companion-*.jar) do (
  echo %%~nf | findstr /i "sources" >nul || copy /y "%%f" "READY-TO-USE\" >nul
)
echo.
echo  Done! Your mod is in the READY-TO-USE folder.
echo  Drag the .jar into Lunar Client's Mods tab (Fabric, 26.3) along with Fabric API.
explorer "READY-TO-USE"
pause
