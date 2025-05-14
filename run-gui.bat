@echo off
REM Run OutlookAlerter in GUI mode on Windows
echo Starting OutlookAlerter in GUI mode...

REM Check if Java is installed
where java >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo Error: Java is not installed or not in the PATH.
    echo Please install Java to run this application.
    pause
    exit /b 1
)

REM Set classpath
set CLASSPATH=dist\OutlookAlerter.jar;lib\*

REM Run the application in GUI mode
java -cp "%CLASSPATH%" com.unhuman.outlookalerter.OutlookAlerter %*

REM Check exit code
if %ERRORLEVEL% NEQ 0 (
    echo OutlookAlerter exited with an error (code: %ERRORLEVEL%).
    echo Check the output above for more details.
    pause
    exit /b %ERRORLEVEL%
)
