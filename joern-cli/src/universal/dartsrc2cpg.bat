@echo off
if not defined DART_ASTGEN set "DART_ASTGEN=%~dp0frontends\dartsrc2cpg\bin\dart_astgen.exe"
call "%~dp0frontends\dartsrc2cpg\bin\dartsrc2cpg.bat" %*
