param([switch]$BuildOnly, [switch]$OrderOnly)
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$sdk = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$bt = Join-Path $sdk 'build-tools\35.0.0'
$android = Join-Path $sdk 'platforms\android-34\android.jar'
$jdk = 'D:\JDK\bin'
$taskBuild = Join-Path $env:TEMP 'nk-ui-regression'
New-Item -ItemType Directory -Force -Path "$taskBuild\classes","$taskBuild\dex" | Out-Null
& "$jdk\javac.exe" -encoding UTF-8 --release 8 -cp $android -d "$taskBuild\classes" (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
if ($LASTEXITCODE) { throw 'UI test compile failed' }
& "$jdk\jar.exe" -cf "$taskBuild\tests.jar" -C "$taskBuild\classes" .
& "$bt\d8.bat" --min-api 26 --lib $android --output "$taskBuild\dex" "$taskBuild\tests.jar"
if ($LASTEXITCODE) { throw 'UI test dex failed' }
& "$bt\aapt2.exe" link -I $android --manifest "$PSScriptRoot\AndroidManifest.xml" -o "$taskBuild\unsigned.apk"
if ($LASTEXITCODE) { throw 'UI test manifest failed' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$taskBuild\unsigned.apk",[System.IO.Compression.ZipArchiveMode]::Update)
try { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$taskBuild\dex\classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
& "$bt\zipalign.exe" -f -P 16 4 "$taskBuild\unsigned.apk" "$taskBuild\aligned.apk"
if (-not $env:NAVKIT_MOD_KS_PASS) {
    $envFile = Join-Path $repo ".keys\local.env"
    if (Test-Path $envFile) {
        foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
            if ($line -match '^NAVKIT_MOD_KS_PASS=(.+)$') { $env:NAVKIT_MOD_KS_PASS = $Matches[1] }
        }
    }
}
if (-not $env:NAVKIT_MOD_KS_PASS) { throw 'NAVKIT_MOD_KS_PASS not set' }
& "$bt\apksigner.bat" sign --ks "$repo\.keys\module-release.jks" --ks-pass env:NAVKIT_MOD_KS_PASS --key-pass env:NAVKIT_MOD_KS_PASS --out "$taskBuild\ui-tests.apk" "$taskBuild\aligned.apk"
if ($LASTEXITCODE) { throw 'UI test signing failed' }
if ($BuildOnly) { Write-Output "Built $taskBuild\ui-tests.apk"; exit 0 }
& adb push "$taskBuild\ui-tests.apk" /data/local/tmp/nk-ui-tests.apk
$size = (Get-Item "$taskBuild\ui-tests.apk").Length
$created = & adb shell "su -c 'cmd package install-create -r -t -g -S $size'"
if ($created -notmatch '\[(\d+)\]') { throw "Test install failed: $created" }
$session = $Matches[1]
& adb shell "su -c 'cmd package install-write -S $size $session base.apk /data/local/tmp/nk-ui-tests.apk'"
& adb shell "su -c 'cmd package install-commit $session'"
$deviceAdb = 'D:\Tools\platform-tools\adb.exe'
$runner = if ($OrderOnly) { 'OrderUiRegression' } else { 'UiRegression' }
$run = Start-Process -FilePath $deviceAdb -ArgumentList @('shell','am','instrument','-w',"org.navkit.core.tests/org.navkit.core.tests.$runner") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$taskBuild\run.txt" -RedirectStandardError "$taskBuild\run.err"
# MIUI can defer an instrumentation-owned background launch; a shell launch grants
# the activity a visible foreground start without changing any device policy.
Start-Sleep -Milliseconds 1500
& adb shell am start --activity-single-top --activity-reorder-to-front -n org.navkit.geoassist/org.navkit.core.MainActivity | Out-Null
if (-not $run.WaitForExit(90000)) { throw 'UI regression timed out' }
$result = Get-Content "$taskBuild\run.txt" -Raw
Write-Output $result
if ($result -notmatch 'OK checks=\d+' -or $result -match 'FAIL ') { throw 'UI regression failed' }
