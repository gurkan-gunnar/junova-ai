[CmdletBinding()]
param(
    [string]$DestinationDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $DestinationDirectory) {
    $DestinationDirectory = Join-Path $repoRoot "models"
}

$modelName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
$modelUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true"
$expectedHash = "626B4A6678B86442240E33DF819E00132D3BA7DDDFE1CDC4FBB18E0A9615C62D"
$modelPath = Join-Path $DestinationDirectory $modelName

New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null

if (-not (Test-Path $modelPath)) {
    Write-Host "Hamtar Qwen2.5 3B till $modelPath"
    & curl.exe --fail --location --retry 5 --continue-at - --output $modelPath $modelUrl
    if ($LASTEXITCODE -ne 0) { throw "Modellnedladdningen misslyckades." }
}

$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $modelPath).Hash
if ($actualHash -ne $expectedHash) {
    throw "Fel SHA-256 for modellen. Forvantat $expectedHash men fick $actualHash."
}

Write-Host "Modellen ar klar och kontrollsumman stammer: $modelPath"
