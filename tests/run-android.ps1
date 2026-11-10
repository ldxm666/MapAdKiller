param([Parameter(Mandatory=$true)][string]$BuildDirectory, [string]$Serial = "")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskBuild = Join-Path $PSScriptRoot 'build'
$sdkRoot = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$androidJar = Join-Path $sdkRoot 'platforms\android-34\android.jar'
$jdkBin = 'D:\JDK\bin'
$adbBin = 'D:\Tools\platform-tools\adb.exe'
if ($Serial) { $env:ANDROID_SERIAL = $Serial }
New-Item -ItemType Directory -Force -Path (Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex') | Out-Null
$moduleJar = Join-Path $BuildDirectory 'build\classes.jar'
$apiClasses = Join-Path $BuildDirectory 'build\stubs'
$serviceJar = Join-Path $projectRoot 'app\libs\service-classes.jar'
$interfaceJar = Join-Path $projectRoot 'app\libs\interface-102.0.0.jar'
& "$jdkBin\javac.exe" -encoding UTF-8 --release 8 -classpath "$androidJar;$moduleJar;$apiClasses;$serviceJar;$interfaceJar" -d "$taskBuild\classes" (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { throw 'Test compile failed' }
& "$jdkBin\jar.exe" -cf "$taskBuild\tests.jar" -C "$taskBuild\classes" .
# API stubs belong only in the separate test dex, never in the release APK.
& "$jdkBin\jar.exe" -cf "$taskBuild\api-test.jar" -C $apiClasses .
& "$sdkRoot\build-tools\35.0.0\d8.bat" --min-api 26 --lib $androidJar --output "$taskBuild\dex" "$taskBuild\tests.jar" "$taskBuild\api-test.jar" $moduleJar $serviceJar $interfaceJar "$projectRoot\app\libs\kotlin-stdlib-1.9.23.jar"
if ($LASTEXITCODE -ne 0) { throw 'Test dex failed' }
& $adbBin push "$taskBuild\dex\classes.dex" /data/local/tmp/mapclean-hotfix-tests.dex
if ($LASTEXITCODE -ne 0) { throw 'Test dex upload failed' }
$failedTests = @()
$testClasses = @(
    'io.github.ldxm666.mapadkiller.AmapSplashTest',
    'io.github.ldxm666.mapadkiller.AmapHomeGeometryTest',
    'io.github.ldxm666.mapadkiller.AmapStartupTest',
    'io.github.ldxm666.mapadkiller.AmapPoiCategoryTest',
    'io.github.ldxm666.tmapclean.TmapStateRecoveryTest',
    'io.github.ldxm666.bmapclean.BaiduConfigRefreshTest',
    'io.github.ldxm666.bmapclean.BaiduCacheIdentityTest',
    'io.github.ldxm666.mapadkiller.AmapConfigRefreshTest',
    'io.github.ldxm666.mapclean.NativeLibraryCacheTest',
    'io.github.ldxm666.bmapclean.BaiduMineCardsTest',
    'io.github.ldxm666.mapadkiller.AmapTabLayoutTest',
    'io.github.ldxm666.tmapclean.TmapAnnotationsTest',
    'io.github.ldxm666.tmapclean.TmapPoiCategoryTest',
    'io.github.ldxm666.mapclean.SystemCardCompatTest',
    'io.github.ldxm666.mapadkiller.AmapDataTest',
    'io.github.ldxm666.mapadkiller.AmapTaxiDataTest',
    'io.github.ldxm666.mapadkiller.AmapTaxiSkinTest',
    'io.github.ldxm666.mapadkiller.AmapNaviScaleTest',
    'io.github.ldxm666.mapadkiller.AmapCruiseTest',
    'io.github.ldxm666.mapadkiller.CfgStoreTest',
    'io.github.ldxm666.mapadkiller.AmapPlaceDataTest',
    'io.github.ldxm666.bmapclean.BaiduLabelStyleTest',
    'io.github.ldxm666.bmapclean.BaiduTabRoutingTest',
    'io.github.ldxm666.bmapclean.BaiduPanelLayoutTest',
    'io.github.ldxm666.bmapclean.BaiduCommuteTest',
    'io.github.ldxm666.bmapclean.BaiduPaymentTest',
    'io.github.ldxm666.tmapclean.TmapPanelLayoutTest',
    'io.github.ldxm666.mapclean.ConfigBackupTest',
    'io.github.ldxm666.mapclean.HookGuardTest',
    'io.github.ldxm666.mapclean.CompatibilityTest',
    'io.github.ldxm666.mapclean.EmbeddedSettingsPolicyTest',
    'io.github.ldxm666.mapclean.FrameworkStatusTest'
)
foreach ($testClass in $testClasses) {
    Write-Output "TEST $testClass"
    & $adbBin shell 'CLASSPATH=/data/local/tmp/mapclean-hotfix-tests.dex' app_process / io.github.ldxm666.mapclean.RegressionHarness $testClass
    $exitCode = $LASTEXITCODE
    Write-Output "EXIT $testClass $exitCode"
    if ($exitCode -ne 0) { $failedTests += $testClass }
}
if ($failedTests.Count) { throw "Tests failed: $($failedTests -join ', ')" }
Write-Output "PASS all $($testClasses.Count) regression groups"
