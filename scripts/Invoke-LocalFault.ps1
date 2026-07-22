[CmdletBinding()]
param([ValidateSet('StopPdf', 'StartPdf')][string]$Action)

. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')

$manifestPath = Join-Path (Get-LocalValidationDirectory) 'processes.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Start-LocalValidation.ps1 must run before a local fault can be injected.' }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$pdf = @($manifest.processes | Where-Object { $_.name -eq 'pdf-service' })[0]
if ($Action -eq 'StopPdf') {
    if ($pdf) { Stop-LocalProcessTree -Id $pdf.id }
    Stop-LocalPortListener -Port 3001
    if (Test-LocalHttpEndpoint 'http://127.0.0.1:3001/health') { throw 'PDF service remained reachable after stop request.' }
    Write-Output 'PDF service stopped for local retry validation.'
    exit 0
}

if (Test-LocalHttpEndpoint 'http://127.0.0.1:3001/health') { Write-Output 'PDF service is already running.'; exit 0 }
$root = Get-LocalValidationRoot
$output = Get-LocalValidationDirectory
$log = Join-Path $output 'pdf-service.log'
$quotedLog = $log.Replace("'", "''")
$process = Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile', '-Command', "& { npm run dev *> '$quotedLog' }" -WorkingDirectory (Join-Path $root 'pdf-service') -PassThru -WindowStyle Hidden
Wait-LocalHttpEndpoint 'http://127.0.0.1:3001/health'
$manifest.processes = @($manifest.processes | Where-Object { $_.name -ne 'pdf-service' }) + @([pscustomobject]@{ name = 'pdf-service'; port = 3001; id = $process.Id; log = $log })
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output 'PDF service restarted for local retry validation.'
