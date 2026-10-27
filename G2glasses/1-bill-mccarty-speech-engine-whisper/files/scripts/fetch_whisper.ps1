# Downloads a multilingual whisper.cpp model and the Silero VAD model, then pushes them to the
# phone with model.txt naming the model to use. Too big for the APK, so they live in the app's
# external files folder: /sdcard/Android/data/com.example.g2glasses/files/whisper/
#
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_whisper.ps1                 # base, download + push
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_whisper.ps1 -Model small    # tiny|base|small|large-v3-turbo
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_whisper.ps1 -SkipPush       # download only
#
# Install the app first (scripts\deploy_phone.ps1). Idempotent: skips downloads that are cached and
# files already on the phone with the right size. Picks the first non-emulator device unless -Serial.
param(
    [ValidateSet("tiny", "base", "small", "large-v3-turbo")][string]$Model = "base",
    [string]$Serial = "",
    [switch]$SkipPush
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# Keep in step with WhisperModel.kt.
$Files = @{
    "tiny"           = "ggml-tiny-q5_1.bin"
    "base"           = "ggml-base-q5_1.bin"
    "small"          = "ggml-small-q5_1.bin"
    "large-v3-turbo" = "ggml-large-v3-turbo-q5_0.bin"
}
$File      = $Files[$Model]
$ModelsUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
$VadUrl    = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
$Package   = "com.example.g2glasses"
$Remote    = "/sdcard/Android/data/$Package/files/whisper"

$Local = Join-Path $PSScriptRoot ".cache\whisper"
New-Item -ItemType Directory -Force $Local | Out-Null

function Get-File($url, $dest) {
    if (Test-Path $dest) { return }
    Write-Host "Downloading $url ..."
    Invoke-WebRequest -Uri $url -OutFile "$dest.part"
    Move-Item -Force "$dest.part" $dest
}

Get-File "$ModelsUrl/$File" (Join-Path $Local $File)
Get-File $VadUrl (Join-Path $Local "silero_vad.onnx")
# No BOM: the app reads model.txt as plain UTF-8.
[System.IO.File]::WriteAllText((Join-Path $Local "model.txt"), "$Model`n")
Write-Host "Whisper $Model ready in $Local"
if ($SkipPush) { exit 0 }

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

foreach ($f in @($File, "silero_vad.onnx", "model.txt")) {
    $path = Join-Path $Local $f
    $size = (Get-Item $path).Length
    # Redirect on the device: PowerShell 5.1 turns native stderr into a terminating error.
    $remoteSize = (& $Adb -s $Serial shell "stat -c %s $Remote/$f 2>/dev/null || true")
    if ($f -ne "model.txt" -and "$remoteSize".Trim() -eq "$size") { Write-Host "$f already on device"; continue }
    Write-Host "Pushing $f ($([math]::Round($size / 1MB)) MB) ..."
    & $Adb -s $Serial push $path "$Remote/$f"
    if ($LASTEXITCODE -ne 0) { throw "adb push failed for $f" }
}
Write-Host "Done. In the app, switch the engine to Whisper (it loads $Model)."
