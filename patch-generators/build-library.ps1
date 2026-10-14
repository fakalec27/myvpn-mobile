param([ValidateSet('arm64','amd64')][string]$Architecture='arm64')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot '../build.ps1') -Native -Architecture $Architecture
if ($LASTEXITCODE -ne 0) { throw 'Public build failed' }
