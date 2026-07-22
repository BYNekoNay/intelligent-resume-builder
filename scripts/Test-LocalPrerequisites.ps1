[CmdletBinding()]
param([ValidateSet('Mock', 'Live')][string]$Mode = 'Mock', [switch]$RequireDocker)
. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')
$root = Get-LocalValidationRoot
Assert-LocalCommand java; Assert-LocalCommand mvn; Assert-LocalCommand node; Assert-LocalCommand npm
Assert-LocalValidationDirectoryIgnored
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
if ($RequireDocker) {
    Assert-LocalCommand docker
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is installed but its engine is not available. Start Docker Desktop before local validation.' }
}
$ErrorActionPreference = $previousErrorAction
$rootEnv = Get-LocalEnvValues -Path (Join-Path $root '.env')
$pdfEnv = Get-LocalEnvValues -Path (Join-Path $root 'pdf-service\.env')
if ($pdfEnv.Count -gt 0 -and $rootEnv['PDF_SERVICE_TOKEN'] -and $pdfEnv['PDF_SERVICE_TOKEN'] -and $rootEnv['PDF_SERVICE_TOKEN'] -ne $pdfEnv['PDF_SERVICE_TOKEN']) { throw 'PDF_SERVICE_TOKEN differs between root .env and pdf-service/.env. Values are intentionally not printed.' }
if ($Mode -eq 'Mock' -and $rootEnv['AI_PROVIDER'] -and $rootEnv['AI_PROVIDER'] -ne 'mock') { throw 'Mock validation requires root .env to use AI_PROVIDER=mock. Put live credentials only in ignored .env.live-ai.' }
if ($Mode -eq 'Live') {
    if ($rootEnv['AI_PROVIDER'] -eq 'bailian') { throw 'Root .env must stay mock-only. Put live credentials in ignored .env.live-ai instead.' }
    if ([string]::IsNullOrWhiteSpace((Get-LocalEnvValues -Path (Join-Path $root '.env.live-ai'))['BAILIAN_API_KEY'])) { throw 'Live AI mode requires BAILIAN_API_KEY in ignored .env.live-ai.' }
}
[pscustomobject]@{ mode = $Mode; outputDirectory = '.local-validation/'; prerequisites = 'ready' } | ConvertTo-Json
