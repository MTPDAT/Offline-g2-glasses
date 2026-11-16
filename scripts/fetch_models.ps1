# Downloads the Vosk Portuguese small model into G2glasses/app/src/main/assets/model-pt/ and the
# sherpa-onnx Android library (used by the Parakeet engine) into G2glasses/app/libs/.
# Idempotent: skips whatever is already in place. Neither is committed to git.
# Usage (from anywhere):  powershell -ExecutionPolicy Bypass -File scripts\fetch_models.ps1
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar on

$ModelName = "vosk-model-small-pt-0.3"
$Url       = "https://alphacephei.com/vosk/models/$ModelName.zip"
$Uuid      = "pt-small-0.3"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Dest     = Join-Path $RepoRoot "G2glasses\app\src\main\assets\model-pt"
$Cache    = Join-Path $PSScriptRoot ".cache"

# vosk-model-small-pt-0.3 uses Vosk's older flat layout (final.mdl, HCLr.fst, Gr.fst, ivector/ at
# the top level, no am/ conf/ graph/ folders). Newer models use am/final.mdl. Vosk loads both.
function Test-ModelComplete {
    foreach ($p in @("ivector", "README", "uuid")) {
        if (-not (Test-Path (Join-Path $Dest $p))) { return $false }
    }
    if (-not ((Test-Path (Join-Path $Dest "final.mdl")) -or (Test-Path (Join-Path $Dest "am\final.mdl")))) { return $false }
    return ((Get-Content (Join-Path $Dest "uuid") -Raw).Trim() -eq $Uuid)
}

# sherpa-onnx AAR (native libs + Kotlin API) for the Parakeet engine; ~50 MB, not on Maven Central.
$SherpaVersion = "1.13.8"
$Aar = Join-Path $RepoRoot "G2glasses\app\libs\sherpa-onnx-$SherpaVersion.aar"
if (-not (Test-Path $Aar)) {
    New-Item -ItemType Directory -Force (Split-Path $Aar) | Out-Null
    $AarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SherpaVersion/sherpa-onnx-$SherpaVersion.aar"
    Write-Host "Downloading $AarUrl ..."
    Invoke-WebRequest -Uri $AarUrl -OutFile "$Aar.part"
    Move-Item -Force "$Aar.part" $Aar
} else {
    Write-Host "sherpa-onnx AAR already present."
}

if (Test-ModelComplete) {
    Write-Host "Model already present in $Dest (uuid $Uuid). Nothing to do."
    exit 0
}

New-Item -ItemType Directory -Force $Cache | Out-Null
$Zip = Join-Path $Cache "$ModelName.zip"
if (-not (Test-Path $Zip)) {
    Write-Host "Downloading $Url ..."
    Invoke-WebRequest -Uri $Url -OutFile "$Zip.part"
    Move-Item -Force "$Zip.part" $Zip
}

$Extract = Join-Path $Cache "extract"
if (Test-Path $Extract) { Remove-Item -Recurse -Force $Extract }
Expand-Archive -Path $Zip -DestinationPath $Extract

# The zip contains a top-level folder; copy its *contents* so there is no extra level.
$Src = Join-Path $Extract $ModelName
if (-not (Test-Path (Join-Path $Src "README"))) { throw "Unexpected zip layout: $Src\README not found" }

if (Test-Path $Dest) { Remove-Item -Recurse -Force $Dest }
New-Item -ItemType Directory -Force $Dest | Out-Null
Copy-Item -Recurse -Path (Join-Path $Src "*") -Destination $Dest

# Vosk's StorageService re-copies the model on the phone whenever this value changes.
[System.IO.File]::WriteAllText((Join-Path $Dest "uuid"), $Uuid)
Remove-Item -Recurse -Force $Extract

if (-not (Test-ModelComplete)) { throw "Model folder incomplete after extraction: $Dest" }
Write-Host "Model ready in $Dest"
