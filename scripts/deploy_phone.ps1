# One command for demo day: check the phone, build, install, launch, and optionally collect metrics.
#
#   powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1              # build + install + launch
#   powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1 -SkipBuild   # reinstall the last APK
#   powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1 -Logcat      # ...then follow the logs
#   powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1 -CollectMetrics   # only dump metrics_<time>.txt
#
# Picks the first USB device that is not an emulator unless -Serial is given.
param(
    [string]$Serial = "",
    [switch]$SkipBuild,
    [switch]$Logcat,
    [switch]$CollectMetrics
)
$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Project  = Join-Path $RepoRoot "G2glasses"
$Apk      = Join-Path $Project "app\build\outputs\apk\debug\app-debug.apk"
$Package  = "com.example.g2glasses"

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# adb: PATH first, then the SDK.
$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $Adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $Adb)) { Fail "adb not found. Install Android SDK Platform-Tools." }
}

# ---- device ----
$lines = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
$devices = $lines | ForEach-Object { $p = $_ -split "\s+"; [pscustomobject]@{ Serial = $p[0]; State = $p[1] } }
if ($Serial) {
    $dev = $devices | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if (-not $dev) { Fail "Device $Serial not connected. Connected: $($devices.Serial -join ', ')" }
} else {
    $dev = $devices | Where-Object { $_.Serial -notlike "emulator-*" } | Select-Object -First 1
    if (-not $dev) {
        Fail ("No phone found over USB. Check: data cable (not charge-only), Developer options > USB debugging on, " +
              "Samsung USB driver installed, Settings > Security and privacy > Auto Blocker off, and tap Allow " +
              "on the phone. Connected: $($devices.Serial -join ', ')")
    }
}
if ($dev.State -eq "unauthorized") { Fail "Phone shows 'unauthorized': unlock it and tap Allow on the USB debugging prompt." }
if ($dev.State -ne "device") { Fail "Device $($dev.Serial) is '$($dev.State)'." }
$S = $dev.Serial
function AdbS { & $Adb -s $S @args }

$model = (AdbS shell getprop ro.product.model).Trim()
$api   = (AdbS shell getprop ro.build.version.sdk).Trim()
$rel   = (AdbS shell getprop ro.build.version.release).Trim()
$page  = (AdbS shell getconf PAGE_SIZE).Trim()
$abi   = (AdbS shell getprop ro.product.cpu.abi).Trim()
Write-Host "Device: $model ($S)  Android $rel / API $api  page size $page  ABI $abi"
if ([int]$api -lt 28) { Fail "API $api is below the app's minSdk 28." }

if ($CollectMetrics) {
    $out = Join-Path (Get-Location) ("metrics_{0}.txt" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
    AdbS logcat -d -s Metrics:V | Out-File -Encoding utf8 $out
    Write-Host "Saved $out  ->  python scripts\score_metrics.py `"$out`""
    exit 0
}

# ---- build ----
if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "fetch_models.ps1")
    Push-Location $Project
    try {
        $ErrorActionPreference = "Continue"   # Gradle writes progress to stderr
        & .\gradlew.bat assembleDebug testDebugUnitTest
        if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }
    } finally {
        $ErrorActionPreference = "Stop"
        Pop-Location
    }
}
if (-not (Test-Path $Apk)) { Fail "APK not found at $Apk. Run without -SkipBuild." }

# ---- install + launch ----
Write-Host "Installing $Apk ..."
AdbS install -r $Apk
if ($LASTEXITCODE -ne 0) {
    Fail ("adb install failed. On Samsung, turn off Settings > Security and privacy > Auto Blocker (it blocks " +
          "installs over USB). INSTALL_FAILED_OLDER_SDK means minSdk is above the phone's API.")
}
AdbS logcat -c
AdbS shell am force-stop $Package   # make sure the new APK starts fresh
AdbS shell am start -n "$Package/.MainActivity" | Out-Null
Write-Host "Launched. First launch needs Wi-Fi for the ML Kit packs (~30 MB each) until the app says Listening."

if ($Logcat) {
    AdbS logcat -s Metrics:V TranslationPipeline:V MainActivity:V AndroidRuntime:E
}
