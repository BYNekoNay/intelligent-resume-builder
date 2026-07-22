[CmdletBinding()]
param([string]$SecretFile = (Join-Path $PSScriptRoot '..\.env.live-ai'))

. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')

if ($env:BAILIAN_LIVE_TEST -ne 'true') { throw 'Set BAILIAN_LIVE_TEST=true explicitly before running the live AI gate.' }
Import-LiveAiEnvironment -Path $SecretFile
$root = Get-LocalValidationRoot
Push-Location (Join-Path $root 'server')
try {
    & mvn test '-Dtest=BailianAiProviderLiveIT'
    if ($LASTEXITCODE -ne 0) { throw 'Bailian live provider gate failed.' }
} finally { Pop-Location }
