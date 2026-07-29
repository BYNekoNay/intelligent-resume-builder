[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')
$path = Join-Path (Get-LocalValidationDirectory) 'processes.json'
if (-not (Test-Path -LiteralPath $path)) { Write-Output 'No local validation process manifest found.'; exit 0 }
$manifest = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
foreach ($process in $manifest.processes) {
    Stop-LocalProcessTree -Id $process.id
    if ($process.port) { Stop-LocalPortListener -Port $process.port }
}
if ($manifest.PSObject.Properties.Name -contains 'databaseCleanup') {
    Remove-DisposableMySqlDatabase -Schema $manifest.databaseCleanup.schema -User $manifest.databaseCleanup.user
}
Remove-Item -Force -ErrorAction SilentlyContinue $path
Write-Output 'Local validation processes stopped.'
