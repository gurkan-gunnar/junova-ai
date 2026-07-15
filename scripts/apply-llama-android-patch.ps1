[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$vendorRoot = Join-Path $repoRoot "vendor\llama.cpp"
$patchFile = Join-Path $repoRoot "patches\llama-android-build.patch"

if (-not (Test-Path (Join-Path $vendorRoot ".git"))) {
    throw "llama.cpp-submodulen saknas. Kor: git submodule update --init --recursive"
}

$ErrorActionPreference = "Continue"
& git -C $vendorRoot apply --unidiff-zero --check $patchFile 2>$null
$canApply = $LASTEXITCODE -eq 0
$ErrorActionPreference = "Stop"
if ($canApply) {
    & git -C $vendorRoot apply --unidiff-zero $patchFile
    if ($LASTEXITCODE -ne 0) { throw "Kunde inte applicera Android-patchen." }
    Write-Host "Android-patchen for llama.cpp ar applicerad."
    exit 0
}

$ErrorActionPreference = "Continue"
& git -C $vendorRoot apply --unidiff-zero --reverse --check $patchFile 2>$null
$alreadyApplied = $LASTEXITCODE -eq 0
$ErrorActionPreference = "Stop"
if ($alreadyApplied) {
    Write-Host "Android-patchen ar redan applicerad."
    exit 0
}

throw "llama.cpp har andra lokala andringar. Kontrollera submodulen innan patchen appliceras."
