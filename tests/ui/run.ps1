param([switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$sdk = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$bt = Join-Path $sdk 'build-tools\35.0.0'
$android = Join-Path $sdk 'platforms\android-34\android.jar'
$jdk = 'D:\JDK\bin'
$taskBuild = Join-Path $env:TEMP 'mapclean-ui-regression'
New-Item -ItemType Directory -Force -Path "$taskBuild\classes","$taskBuild\dex" | Out-Null
& "$jdk\javac.exe" -encoding UTF-8 --release 8 -cp $android -d "$taskBuild\classes" "$PSScriptRoot\UiRegression.java"
if ($LASTEXITCODE) { throw 'UI test compile failed' }
& "$jdk\jar.exe" -cf "$taskBuild\tests.jar" -C "$taskBuild\classes" .
& "$bt\d8.bat" --min-api 26 --lib $android --output "$taskBuild\dex" "$taskBuild\tests.jar"
if ($LASTEXITCODE) { throw 'UI test dex failed' }
& "$bt\aapt2.exe" link -I $android --manifest "$PSScriptRoot\AndroidManifest.xml" -o "$taskBuild\unsigned.apk"
if ($LASTEXITCODE) { throw 'UI test manifest failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$taskBuild\unsigned.apk",[System.IO.Compression.ZipArchiveMode]::Update)
try { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$taskBuild\dex\classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
& "$bt\zipalign.exe" -f -P 16 4 "$taskBuild\unsigned.apk" "$taskBuild\aligned.apk"
& "$bt\apksigner.bat" sign --ks "$repo\app\debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$taskBuild\ui-tests.apk" "$taskBuild\aligned.apk"
if ($LASTEXITCODE) { throw 'UI test signing failed' }
if ($BuildOnly) { Write-Output "Built $taskBuild\ui-tests.apk"; exit 0 }
& adb push "$taskBuild\ui-tests.apk" /data/local/tmp/mapclean-ui-tests.apk
$size = (Get-Item "$taskBuild\ui-tests.apk").Length
$created = & adb shell "su -c 'cmd package install-create -r -t -g -S $size'"
if ($created -notmatch '\[(\d+)\]') { throw "Test install failed: $created" }
$session = $Matches[1]
& adb shell "su -c 'cmd package install-write -S $size $session base.apk /data/local/tmp/mapclean-ui-tests.apk'"
& adb shell "su -c 'cmd package install-commit $session'"
$deviceAdb = 'D:\Tools\platform-tools\adb.exe'
$run = Start-Process -FilePath $deviceAdb -ArgumentList @('shell','am','instrument','-w','io.github.ldxm666.mapclean.tests/io.github.ldxm666.mapclean.tests.UiRegression') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$taskBuild\run.txt" -RedirectStandardError "$taskBuild\run.err"
# MIUI can defer an instrumentation-owned background launch; a shell launch grants
# the activity a visible foreground start without changing any device policy.
Start-Sleep -Milliseconds 1500
& adb shell am start --activity-single-top --activity-reorder-to-front -n io.github.ldxm666.mapadkiller/io.github.ldxm666.mapclean.MainActivity | Out-Null
if (-not $run.WaitForExit(90000)) { throw 'UI regression timed out' }
$result = Get-Content "$taskBuild\run.txt" -Raw
Write-Output $result
if ($result -notmatch 'OK checks=\d+' -or $result -match 'FAIL ') { throw 'UI regression failed' }
