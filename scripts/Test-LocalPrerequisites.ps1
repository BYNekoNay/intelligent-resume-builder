[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')
$root = Get-LocalValidationRoot
Assert-LocalCommand java; Assert-LocalCommand mvn; Assert-LocalCommand node; Assert-LocalCommand npm
Assert-LocalValidationDirectoryIgnored
$rootEnv = Get-LocalEnvValues -Path (Join-Path $root '.env')
$pdfEnv = Get-LocalEnvValues -Path (Join-Path $root 'pdf-service\.env')
if ($pdfEnv.Count -gt 0 -and $rootEnv['PDF_SERVICE_TOKEN'] -and $pdfEnv['PDF_SERVICE_TOKEN'] -and $rootEnv['PDF_SERVICE_TOKEN'] -ne $pdfEnv['PDF_SERVICE_TOKEN']) { throw 'PDF_SERVICE_TOKEN differs between root .env and pdf-service/.env. Values are intentionally not printed.' }
if ([string]::IsNullOrWhiteSpace((Get-LocalEnvValues -Path (Join-Path $root '.env.live-ai'))['BAILIAN_API_KEY'])) { throw 'Local validation requires BAILIAN_API_KEY in ignored .env.live-ai.' }
[pscustomobject]@{ mode = 'live'; database = 'local-mysql'; outputDirectory = '.local-validation/'; prerequisites = 'ready' } | ConvertTo-Json
