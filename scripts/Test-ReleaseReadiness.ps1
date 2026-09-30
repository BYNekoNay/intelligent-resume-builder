[CmdletBinding()]
param(
    [switch]$SkipBackendTests,
    [switch]$SkipFrontendE2E
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Invoke-CheckedCommand {
    param([string]$WorkingDirectory, [string]$Command)
    Push-Location $WorkingDirectory
    try {
        Invoke-Expression $Command
        if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command" }
    } finally {
        Pop-Location
    }
}

Invoke-CheckedCommand -WorkingDirectory $root -Command 'git diff --check'
Invoke-CheckedCommand -WorkingDirectory (Join-Path $root 'deploy') -Command 'docker compose --env-file production.env.example -f docker-compose.prod.yml config --quiet'
# 分两步执行：复合命令（`npm run check; npm test`）只回传末位退出码，
# 前一步失败会被静默掩盖（ideation #21）。
Invoke-CheckedCommand -WorkingDirectory (Join-Path $root 'pdf-service') -Command 'npm run check'
Invoke-CheckedCommand -WorkingDirectory (Join-Path $root 'pdf-service') -Command 'npm test'

if (-not $SkipBackendTests) {
    Invoke-CheckedCommand -WorkingDirectory (Join-Path $root 'server') -Command 'mvn -q test'
}

Invoke-CheckedCommand -WorkingDirectory (Join-Path $root 'web') -Command 'npm run build'
if (-not $SkipFrontendE2E) {
    Invoke-CheckedCommand -WorkingDirectory (Join-Path $root 'web') -Command 'npm run test:e2e'
}

Write-Host 'Release readiness checks passed. No containers were started by this script.'
