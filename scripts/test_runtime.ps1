param(
    [string]$Serial = 'emulator-5580',
    [string]$BuildTools = "$env:LOCALAPPDATA/Android/Sdk/build-tools/34.0.0",
    [switch]$Integration,
    [switch]$LiveSources,
    [ValidatePattern('^[a-z0-9]+(,[a-z0-9]+)*$')][string]$SourceIds
)
$ErrorActionPreference = 'Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'These checks install only on an explicitly selected emulator.' }
$root = Split-Path -Parent $PSScriptRoot
$sdk = Split-Path -Parent (Split-Path -Parent $BuildTools)
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$run = Join-Path $root ('builds/runtime-tests/' + [Guid]::NewGuid().ToString('N'))
& "$PSScriptRoot/build_runtime.ps1" -OutputDirectory $run -BuildTools $BuildTools -Tests
& "$BuildTools/aapt.exe" package -f -M "$root/runtime/tests/AndroidManifest.xml" -I "$sdk/platforms/android-34/android.jar" -F "$run/test-unsigned.apk"
if($LASTEXITCODE -ne 0) { throw 'Test APK packaging failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$run/test-unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$run/classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
& "$PSScriptRoot/sign_release.ps1" -InputApk "$run/test-unsigned.apk" -OutputApk "$run/test.apk" -BuildTools $BuildTools
& $adb -s $Serial install -r "$run/test.apk"
if($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
$result = & $adb -s $Serial shell am instrument -w com.mangaku.local.tests/com.mangaku.local.RuntimeChecks
$result | Set-Content "$run/results.txt" -Encoding utf8
$result
if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'PASS \d+ scenarios') { throw "Android runtime checks failed: $run/results.txt" }
Write-Output "Test evidence: $run/results.txt"
$sourceResult = & $adb -s $Serial shell am instrument -w com.mangaku.local.tests/com.mangaku.local.SourceChecks
$sourceResult | Set-Content "$run/sources.txt" -Encoding utf8
$sourceResult
if($LASTEXITCODE -ne 0 -or ($sourceResult -join "`n") -notmatch 'PASS source checks:') { throw "Source checks failed: $run/sources.txt" }
if($LiveSources) {
    $instrumentArgs = @('-s',$Serial,'shell','am','instrument','-w','-e','live','true')
    if($SourceIds) { $instrumentArgs += @('-e','only',$SourceIds) }
    $liveResult = & $adb @instrumentArgs com.mangaku.local.tests/com.mangaku.local.SourceChecks
    $liveResult | Set-Content "$run/source-live-log.txt" -Encoding utf8
    $liveResult
    if($LASTEXITCODE -ne 0 -or ($liveResult -join "`n") -notmatch 'SOURCE LIVE MATRIX') { throw "Live source check did not complete: $run/source-live-log.txt" }
    $matrix = @($liveResult | Where-Object { $_.StartsWith('[{') })
    if($matrix.Count -ne 1) { throw 'Expected one completed source matrix in the instrumentation output.' }
    $rows = @($matrix[0] | ConvertFrom-Json)
    $matrix[0] | Set-Content "$run/source-live-results.json" -Encoding utf8
    $rows | Group-Object status | Select-Object Name,Count | Format-Table
    Write-Output "Live evidence (including unavailable sources): $run/source-live-results.json"
    if(@($rows | Where-Object status -ne 'passed').Count) { Write-Warning 'Some sources failed or passed only partially. Inspect the saved matrix before claiming support.' }
}
if($Integration) {
    $integrationResult = & $adb -s $Serial shell am instrument -w com.mangaku.local.tests/com.mangaku.local.IntegrationChecks
    $integrationResult | Set-Content "$run/integration.txt" -Encoding utf8
    $integrationResult
    if($LASTEXITCODE -ne 0 -or ($integrationResult -join "`n") -notmatch 'PASS installed release integration') { throw "Installed release integration failed: $run/integration.txt" }
}
