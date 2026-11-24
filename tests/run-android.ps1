param([Parameter(Mandatory=$true)][string]$BuildDirectory, [string]$Serial = "", [string]$TestFilter = "")
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
& $adbBin push "$taskBuild\dex\classes.dex" /data/local/tmp/nk-unit-tests.dex
if ($LASTEXITCODE -ne 0) { throw 'Test dex upload failed' }
$failedTests = @()
$testClasses = @(
    'org.navkit.geoassist.AmapSplashTest',
    'org.navkit.geoassist.AmapToolOrderTest',
    'org.navkit.geoassist.AmapToolOrderBackupTest',
    'org.navkit.geoassist.AmapHomeGeometryTest',
    'org.navkit.geoassist.AmapStartupTest',
    'org.navkit.geoassist.AmapPoiCategoryTest',
    'org.navkit.tgeo.TmapStateRecoveryTest',
    'org.navkit.tgeo.TmapHomeBannerTest',
    'org.navkit.bgeo.BaiduConfigRefreshTest',
    'org.navkit.bgeo.BaiduCacheIdentityTest',
    'org.navkit.geoassist.AmapConfigRefreshTest',
    'org.navkit.core.NativeLibraryCacheTest',
    'org.navkit.bgeo.BaiduMineCardsTest',
    'org.navkit.geoassist.AmapTabLayoutTest',
    'org.navkit.tgeo.TmapAnnotationsTest',
    'org.navkit.tgeo.TmapPoiCategoryTest',
    'org.navkit.core.SystemCardCompatTest',
    'org.navkit.geoassist.AmapDataTest',
    'org.navkit.geoassist.AmapCardRulesTest',
    'org.navkit.geoassist.AmapMapWidgetTest',
    'org.navkit.geoassist.AmapTaxiDataTest',
    'org.navkit.geoassist.AmapTaxiSkinTest',
    'org.navkit.geoassist.AmapNaviScaleTest',
    'org.navkit.geoassist.AmapCruiseTest',
    'org.navkit.geoassist.CfgStoreTest',
    'org.navkit.geoassist.AmapPlaceDataTest',
    'org.navkit.bgeo.BaiduLabelStyleTest',
    'org.navkit.bgeo.BaiduTabRoutingTest',
    'org.navkit.bgeo.BaiduHomeReselectTest',
    'org.navkit.bgeo.BaiduPanelLayoutTest',
    'org.navkit.bgeo.BaiduCommuteTest',
    'org.navkit.bgeo.BaiduPaymentTest',
    'org.navkit.tgeo.TmapPanelLayoutTest',
    'org.navkit.core.ConfigBackupTest',
    'org.navkit.core.HookGuardTest',
    'org.navkit.core.CompatibilityTest',
    'org.navkit.core.EmbeddedSettingsPolicyTest',
    'org.navkit.core.FrameworkStatusTest'
)
if ($TestFilter) {
    $testClasses = @($testClasses | Where-Object { $_ -match $TestFilter })
    if (-not $testClasses.Count) { throw "No regression groups match: $TestFilter" }
}
foreach ($testClass in $testClasses) {
    Write-Output "TEST $testClass"
    & $adbBin shell 'CLASSPATH=/data/local/tmp/nk-unit-tests.dex' app_process / org.navkit.core.RegressionHarness $testClass
    $exitCode = $LASTEXITCODE
    Write-Output "EXIT $testClass $exitCode"
    if ($exitCode -ne 0) { $failedTests += $testClass }
}
if ($failedTests.Count) { throw "Tests failed: $($failedTests -join ', ')" }
Write-Output "PASS all $($testClasses.Count) regression groups"
