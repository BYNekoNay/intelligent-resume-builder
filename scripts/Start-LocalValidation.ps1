[CmdletBinding()]
param(
    [switch]$SkipWeb,
    [switch]$DisposableDatabase,
    [string]$CloneDatabase
)

. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')

$root = Get-LocalValidationRoot
& (Join-Path $PSScriptRoot 'Test-LocalPrerequisites.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Prerequisite validation failed.' }
Import-LiveAiEnvironment -Path (Join-Path $root '.env.live-ai')
$env:SPRING_PROFILES_ACTIVE = 'local-mysql'
$disposable = $null
if ($DisposableDatabase -and -not [string]::IsNullOrWhiteSpace($CloneDatabase)) {
    throw 'Use either -DisposableDatabase or -CloneDatabase, not both.'
}
$isolatedDatabaseRequested = $DisposableDatabase -or -not [string]::IsNullOrWhiteSpace($CloneDatabase)
if ($isolatedDatabaseRequested -and (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:8080/actuator/health')) {
    throw 'Port 8080 already has a healthy server. Stop the local validation environment before requesting an isolated database.'
}
if ($isolatedDatabaseRequested) {
    $disposable = New-DisposableMySqlDatabase -SourceSchema $CloneDatabase
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3306/$($disposable.Schema)?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
    $env:SPRING_DATASOURCE_USERNAME = $disposable.User
    $env:SPRING_DATASOURCE_PASSWORD = $disposable.Password
    if ($disposable.RequiresBaseline) {
        $env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'true'
        $env:SPRING_FLYWAY_BASELINE_VERSION = '19'
        $env:SPRING_FLYWAY_BASELINE_DESCRIPTION = 'Isolated V19 business-data clone'
    }
}

$output = Get-LocalValidationDirectory
$processes = @()

# O-01: 让 pdf-service 的 --env-file=.env 与根 .env 的 PDF_SERVICE_TOKEN 保持一致。
# Node 的 --env-file 在文件不存在时会直接报错，因此这里确保 pdf-service/.env 存在；
# 同时把根 .env 的 token 注入进程环境（环境变量优先于 env-file，保证两侧天然一致）。
$rootEnv = Get-LocalEnvValues -Path (Join-Path $root '.env')
$pdfToken = if (-not [string]::IsNullOrWhiteSpace($rootEnv['PDF_SERVICE_TOKEN'])) { $rootEnv['PDF_SERVICE_TOKEN'] } else { 'dev-pdf-token-change-me' }
$env:PDF_SERVICE_TOKEN = $pdfToken
$pdfEnvPath = Join-Path $root 'pdf-service\.env'
if (-not (Test-Path -LiteralPath $pdfEnvPath)) {
    Set-Content -LiteralPath $pdfEnvPath -Value "PDF_SERVICE_TOKEN=$pdfToken" -Encoding utf8
}

function Start-ValidationProcess {
    param([string]$Name, [int]$Port, [string]$WorkingDirectory, [string]$Command)
    $log = Join-Path $output "$Name.log"
    $quotedLog = $log.Replace("'", "''")
    $process = Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile', '-Command', "& { $Command *> '$quotedLog' }" -WorkingDirectory $WorkingDirectory -PassThru -WindowStyle Hidden
    return @{ name = $Name; port = $Port; id = $process.Id; log = $log }
}

try {
    if (-not (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:3001/health')) { $processes += Start-ValidationProcess -Name 'pdf-service' -Port 3001 -WorkingDirectory (Join-Path $root 'pdf-service') -Command 'npm run dev' }
    if (-not (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:8080/actuator/health')) { $processes += Start-ValidationProcess -Name 'server' -Port 8080 -WorkingDirectory (Join-Path $root 'server') -Command 'mvn spring-boot:run' }
    if (-not $SkipWeb -and -not (Test-LocalHttpEndpoint -Uri 'http://127.0.0.1:5173')) { $processes += Start-ValidationProcess -Name 'web' -Port 5173 -WorkingDirectory (Join-Path $root 'web') -Command '$env:VITE_API_BASE_URL=''http://127.0.0.1:8080''; npm run dev -- --host 127.0.0.1 --port 5173' }
    Wait-LocalHttpEndpoint -Uri 'http://127.0.0.1:3001/health'
    Wait-LocalHttpEndpoint -Uri 'http://127.0.0.1:8080/actuator/health'
    if (-not $SkipWeb) { Wait-LocalHttpEndpoint -Uri 'http://127.0.0.1:5173' }
    $databaseMode = if (-not [string]::IsNullOrWhiteSpace($CloneDatabase)) { 'cloned-local-mysql' } elseif ($disposable) { 'disposable-mysql' } else { 'local-mysql' }
    $summary = @{ mode = 'live'; database = $databaseMode; startedAt = (Get-Date).ToUniversalTime().ToString('o'); processes = $processes }
    if ($disposable) { $summary.databaseCleanup = @{ schema = $disposable.Schema; user = $disposable.User } }
    Write-LocalValidationSummary -Name 'processes.json' -Summary $summary | Write-Output
} catch {
    foreach ($process in $processes) { Stop-LocalProcessTree -Id $process.id }
    if ($disposable) { Remove-DisposableMySqlDatabase -Schema $disposable.Schema -User $disposable.User }
    throw
}
