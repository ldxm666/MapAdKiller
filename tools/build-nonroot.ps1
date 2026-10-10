param(
    [Parameter(Mandatory=$true)][string]$ModuleApk,
    [Parameter(Mandatory=$true)][string[]]$OriginalApks,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateSet('integrated','manager')][string]$Mode = 'integrated',
    [ValidateSet(0,1,2,3)][int]$SignatureBypass = 2,
    [string]$LspatchJar,
    [string]$CacheDirectory,
    [string]$Java = 'java',
    [string]$Python = 'python',
    [string]$AndroidSdk,
    [string]$BuildToolsVersion,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$builderArguments = @((Join-Path $PSScriptRoot 'build_nonroot.py'),
    '--ModuleApk', $ModuleApk, '--OriginalApks') + $OriginalApks + @(
    '--OutputDirectory', $OutputDirectory, '--Mode', $Mode,
    '--SignatureBypass', "$SignatureBypass", '--Java', $Java)
if ($LspatchJar) { $builderArguments += @('--LspatchJar', $LspatchJar) }
if ($CacheDirectory) { $builderArguments += @('--CacheDirectory', $CacheDirectory) }
if ($AndroidSdk) { $builderArguments += @('--AndroidSdk', $AndroidSdk) }
if ($BuildToolsVersion) { $builderArguments += @('--BuildToolsVersion', $BuildToolsVersion) }
if ($VerifyOnly) { $builderArguments += '--VerifyOnly' }
& $Python @builderArguments
if ($LASTEXITCODE -ne 0) { throw "Rootless APK packaging or verification failed (exit $LASTEXITCODE)." }
