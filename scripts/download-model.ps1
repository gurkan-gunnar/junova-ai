[CmdletBinding()]
param(
    [string]$DestinationDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $DestinationDirectory) {
    $DestinationDirectory = Join-Path $repoRoot "models"
}

$modelName = "gemma-4-E2B_q4_0-it.gguf"
$modelUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf?download=true"
$expectedHash = "3646B4C147CD235A44D91DF1546D3B7D8E29B547DBE4E1F80856419AA455E6FD"
$modelPath = Join-Path $DestinationDirectory $modelName

New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null

if (-not (Test-Path $modelPath)) {
    Write-Host "Hamtar Gemma 4 5B till $modelPath"
    & curl.exe --fail --location --retry 5 --continue-at - --output $modelPath $modelUrl
    if ($LASTEXITCODE -ne 0) { throw "Modellnedladdningen misslyckades." }
}

$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $modelPath).Hash
if ($actualHash -ne $expectedHash) {
    throw "Fel SHA-256 for modellen. Forvantat $expectedHash men fick $actualHash."
}

Write-Host "Modellen ar klar och kontrollsumman stammer: $modelPath"
