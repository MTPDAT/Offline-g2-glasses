# Downloads the Parakeet TDT 0.6B v3 model (multilingual, includes Portuguese; int8, ~490 MB) and the
# Silero VAD model, then pushes them to the phone. Too big for the APK, so they live in the app's
# external files folder: /sdcard/Android/data/com.example.g2glasses/files/parakeet-pt/
#
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_parakeet.ps1            # download + push
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_parakeet.ps1 -SkipPush  # download only
#
# Install the app first (scripts\deploy_phone.ps1). Idempotent: skips downloads that are cached and
# files already on the phone with the right size. Picks the first non-emulator device unless -Serial.
param([string]$Serial = "", [switch]$SkipPush)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ModelName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
$Base      = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
$Files     = @("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
$Package   = "com.example.g2glasses"
$Remote    = "/sdcard/Android/data/$Package/files/parakeet-pt"

$Cache = Join-Path $PSScriptRoot ".cache"
$Local = Join-Path $Cache "parakeet-pt"
New-Item -ItemType Directory -Force $Local | Out-Null

function Get-File($url, $dest) {
    if (Test-Path $dest) { return }
    Write-Host "Downloading $url ..."
    Invoke-WebRequest -Uri $url -OutFile "$dest.part"
    Move-Item -Force "$dest.part" $dest
}

# ---- download + extract ----
$missing = $Files | Where-Object { -not (Test-Path (Join-Path $Local $_)) }
if ($missing) {
    $Tar = Join-Path $Cache "$ModelName.tar.bz2"
    Get-File "$Base/$ModelName.tar.bz2" $Tar
    Write-Host "Extracting ..."
    $members = $Files | ForEach-Object { "$ModelName/$_" }
    & tar.exe -xjf $Tar -C $Cache @members
    if ($LASTEXITCODE -ne 0) { throw "tar failed on $Tar" }
    foreach ($f in $Files) { Move-Item -Force (Join-Path $Cache "$ModelName\$f") (Join-Path $Local $f) }
    Remove-Item -Recurse -Force (Join-Path $Cache $ModelName)
}
Get-File "$Base/silero_vad.onnx" (Join-Path $Local "silero_vad.onnx")
Write-Host "Model files ready in $Local"
if ($SkipPush) { exit 0 }

# ---- push ----
$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $Adb = Join-Path $sdk "platform-tools\adb.exe"
}
if (-not $Serial) {
    $Serial = (& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" -and $_ -notlike "emulator-*" } |
               Select-Object -First 1) -replace "\s.*", ""
    if (-not $Serial) { throw "No phone connected (adb devices). Use -Serial emulator-5554 for the emulator." }
}
if (-not ((& $Adb -s $Serial shell pm path $Package) -match "package:")) {
    throw "$Package is not installed on $Serial. Run scripts\deploy_phone.ps1 first."
}
# Let the app create its own external files folder first, so it owns it.
& $Adb -s $Serial shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
& $Adb -s $Serial shell mkdir -p $Remote | Out-Null

foreach ($f in $Files + "silero_vad.onnx") {
    $path = Join-Path $Local $f
    $size = (Get-Item $path).Length
    # Redirect on the device: PowerShell 5.1 turns native stderr into a terminating error.
    $remoteSize = (& $Adb -s $Serial shell "stat -c %s $Remote/$f 2>/dev/null || true")
    if ("$remoteSize".Trim() -eq "$size") { Write-Host "$f already on device"; continue }
    Write-Host "Pushing $f ($([math]::Round($size / 1MB)) MB) ..."
    & $Adb -s $Serial push $path "$Remote/$f"
    if ($LASTEXITCODE -ne 0) { throw "adb push failed for $f" }
}
Write-Host "Done. In the app, switch the engine to Parakeet."
