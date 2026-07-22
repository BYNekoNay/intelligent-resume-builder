[CmdletBinding()]
param(
    [ValidateSet('Mock', 'Live')][string]$Mode = 'Mock',
    [switch]$SkipWeb,
    [switch]$SkipMySql
)

. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')

$root = Get-LocalValidationRoot
& (Join-Path $PSScriptRoot 'Test-LocalPrerequisites.ps1') -Mode $Mode -RequireDocker:(-not $SkipMySql)
if ($LASTEXITCODE -ne 0) { throw 'Prerequisite validation failed.' }
if ($Mode -eq 'Live') { Import-LiveAiEnvironment -Path (Join-Path $root '.env.live-ai') }
$env:SPRING_PROFILES_ACTIVE = 'local-mysql'
if (-not $SkipMySql) {
    & docker compose -f (Join-Path $root 'docker-compose.yml') up -d mysql
    if ($LASTEXITCODE -ne 0) { throw 'MySQL container failed to start.' }
}

$output = Get-LocalValidationDirectory
$processes = @()
function Start-ValidationProcess {
    param([string]$Name, [int]$Port, [string]$WorkingDirectory, [string]$Command)
    $log = Join-Path $output "$Name.log"
    $quotedLog = $log.Replace("'", "''")
    $process = Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile', '-Command', "& { $Command *> '$quotedLog' }" -WorkingDirectory $WorkingDirectory -PassThru -WindowStyle Hidden
    return @{ name = $Name; port = $Port; id = $process.Id; log = $log }
}

try {
    if (-not (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:3001/health')) { $processes += Start-ValidationProcess -Name 'pdf-service' -Port 3001 -WorkingDirectory (Join-Path $root 'pdf-service') -Command 'npm run dev' }
    if (-not (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:8080/api/system/health')) { $processes += Start-ValidationProcess -Name 'server' -Port 8080 -WorkingDirectory (Join-Path $root 'server') -Command 'mvn spring-boot:run' }
    if (-not $SkipWeb -and -not (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:5173')) { $processes += Start-ValidationProcess -Name 'web' -Port 5173 -WorkingDirectory (Join-Path $root 'web') -Command '$env:VITE_API_BASE_URL=''http://127.0.0.1:8080''; npm run dev -- --host 127.0.0.1 --port 5173' }
    Wait-LocalHttpEndpoint -Uri 'http://127.0.0.1:3001/health'
    Wait-LocalHttpEndpoint -Uri 'http://127.0.0.1:8080/api/system/health'
    if (-not $SkipWeb) { Wait-LocalHttpEndpoint -Uri 'http://127.0.0.1:5173' }
    Write-LocalValidationSummary -Name 'processes.json' -Summary @{ mode = $Mode; startedAt = (Get-Date).ToUniversalTime().ToString('o'); processes = $processes } | Write-Output
} catch {
    foreach ($process in $processes) { Stop-LocalProcessTree -Id $process.id }
    throw
}
