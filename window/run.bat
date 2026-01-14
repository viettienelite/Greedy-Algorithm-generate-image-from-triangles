@echo off
echo Dang tim Java...
if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
) else (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
)

echo Bien dich GreedyPrimitive...
"%JAVA_HOME%\bin\javac" GreedyPrimitive.java

echo Dang chay...
"%JAVA_HOME%\bin\java" GreedyPrimitive
pause