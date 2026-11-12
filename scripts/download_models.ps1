param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$AssetsDir = Join-Path $RepoRoot "android\app\src\main\assets"
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null

$Downloads = @(
    @{
        Name = "yolo11n.onnx"
        Url  = "https://huggingface.co/aaurelions/yolo11n.onnx/resolve/main/yolo11n.onnx"
    },
    @{
        Name = "efficientdet_lite2_int8.tflite"
        Url  = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/latest/efficientdet_lite2.tflite"
    },
    @{
        Name = "hand_landmarker.task"
        Url  = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
    },
    @{
        Name = "mobilenet_v3_small.tflite"
        Url  = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite"
    }
)

foreach ($item in $Downloads) {
    $dest = Join-Path $AssetsDir $item.Name
    if (-not $Force -and (Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)) {
        Write-Host "[OK] $($item.Name) already exists ($([math]::Round((Get-Item $dest).Length / 1MB, 2)) MB)"
        continue
    }
    Write-Host "[GET] Downloading $($item.Name)..."
    Invoke-WebRequest -Uri $item.Url -OutFile "$dest.tmp" -UseBasicParsing
    Move-Item -Force "$dest.tmp" $dest
    Write-Host "[OK] Saved $($item.Name) ($([math]::Round((Get-Item $dest).Length / 1MB, 2)) MB)"
}

$VoskDir = Join-Path $AssetsDir "model-en-us"
$VoskMdl = Join-Path $VoskDir "am\final.mdl"
if (-not $Force -and (Test-Path $VoskMdl)) {
    Write-Host "[OK] Vosk model-en-us already exists"
} else {
    $ZipPath = Join-Path $AssetsDir "vosk-model-small-en-us-0.15.zip"
    $ExtractRoot = Join-Path $AssetsDir "_vosk_tmp"
    Write-Host "[GET] Downloading vosk-model-small-en-us-0.15.zip..."
    Invoke-WebRequest -Uri "https://alphacephei.com/kaldi/models/vosk-model-small-en-us-0.15.zip" -OutFile $ZipPath -UseBasicParsing
    Remove-Item -Recurse -Force $ExtractRoot, $VoskDir -ErrorAction SilentlyContinue
    Expand-Archive -Path $ZipPath -DestinationPath $ExtractRoot -Force
    $Inner = Get-ChildItem -Directory $ExtractRoot | Select-Object -First 1
    Move-Item -Force $Inner.FullName $VoskDir
    Remove-Item -Recurse -Force $ExtractRoot, $ZipPath -ErrorAction SilentlyContinue
    Write-Host "[OK] Extracted Vosk offline speech model to model-en-us/"
}

Write-Host "`nAll on-device models are ready in: $AssetsDir"
Get-ChildItem $AssetsDir | Select-Object Name, @{N="SizeMB";E={if ($_.PSIsContainer) { "<dir>" } else { [math]::Round($_.Length / 1MB, 2) }}} | Format-Table -AutoSize
