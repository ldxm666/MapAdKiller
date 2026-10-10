param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskBuild = Join-Path $PSScriptRoot 'build'
$sdkRoot = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$androidJar = Join-Path $sdkRoot 'platforms\android-34\android.jar'
$jdkBin = 'D:\JDK\bin'
$adbBin = 'D:\Tools\platform-tools\adb.exe'
New-Item -ItemType Directory -Force -Path (Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex') | Out-Null
$moduleJar = Join-Path $BuildDirectory 'build\classes.jar'
$apiClasses = Join-Path $BuildDirectory 'build\stubs'
& "$jdkBin\javac.exe" -encoding UTF-8 --release 8 -classpath "$androidJar;$moduleJar;$apiClasses" -d "$taskBuild\classes" (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { throw 'Test compile failed' }
& "$jdkBin\jar.exe" -cf "$taskBuild\tests.jar" -C "$taskBuild\classes" .
# API stubs belong only in the separate test dex, never in the release APK.
& "$jdkBin\jar.exe" -cf "$taskBuild\api-test.jar" -C $apiClasses .
& "$sdkRoot\build-tools\35.0.0\d8.bat" --min-api 26 --lib $androidJar --output "$taskBuild\dex" "$taskBuild\tests.jar" "$taskBuild\api-test.jar" $moduleJar
if ($LASTEXITCODE -ne 0) { throw 'Test dex failed' }
& $adbBin push "$taskBuild\dex\classes.dex" /data/local/tmp/mapclean-hotfix-tests.dex
if ($LASTEXITCODE -ne 0) { throw 'Test dex upload failed' }
foreach ($testClass in @('io.github.ldxm666.mapadkiller.AmapDataTest','io.github.ldxm666.mapadkiller.AmapPlaceDataTest','io.github.ldxm666.mapclean.ConfigBackupTest','io.github.ldxm666.mapclean.HookGuardTest','io.github.ldxm666.mapclean.CompatibilityTest')) {
    & $adbBin shell 'CLASSPATH=/data/local/tmp/mapclean-hotfix-tests.dex' app_process / $testClass
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $testClass" }
}
