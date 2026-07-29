Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-LocalValidationRoot { return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }

function Assert-LocalCommand {
    param([Parameter(Mandatory = $true)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw "Required command '$Name' was not found on PATH." }
}

function Get-LocalEnvValues {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return @{} }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) { continue }
        $name, $value = $trimmed.Split('=', 2)
        $values[$name.Trim()] = $value.Trim()
    }
    return $values
}

function Test-LocalHttpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 2)
    try { $response = Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSeconds -UseBasicParsing; return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300 } catch { return $false }
}

function Wait-LocalHttpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do { if (Test-LocalHttpEndpoint -Uri $Uri) { return }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri."
}

function Stop-LocalProcessTree {
    param([Parameter(Mandatory = $true)][int]$Id)
    foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $Id" -ErrorAction SilentlyContinue)) {
        Stop-LocalProcessTree -Id $child.ProcessId
    }
    Stop-Process -Id $Id -Force -ErrorAction SilentlyContinue
}

function Stop-LocalPortListener {
    param([Parameter(Mandatory = $true)][int]$Port)
    foreach ($listener in @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        Stop-LocalProcessTree -Id $listener.OwningProcess
    }
}

function Get-LocalValidationDirectory {
    $path = Join-Path (Get-LocalValidationRoot) '.local-validation'
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return $path
}

function Assert-LocalValidationDirectoryIgnored {
    $root = Get-LocalValidationRoot
    $path = Get-LocalValidationDirectory
    $probe = Join-Path $path '.gitignore-probe'
    New-Item -ItemType File -Force -Path $probe | Out-Null
    try { & git -C $root check-ignore --quiet -- $probe; if ($LASTEXITCODE -ne 0) { throw "Local validation output directory must be ignored by Git: $path" } } finally { Remove-Item -Force -ErrorAction SilentlyContinue $probe }
}

function Write-LocalValidationSummary {
    param([Parameter(Mandatory = $true)][hashtable]$Summary, [string]$Name = 'summary.json')
    Assert-LocalValidationDirectoryIgnored
    $directory = Get-LocalValidationDirectory
    $path = Join-Path $directory $Name
    $Summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8
    if ($Name.EndsWith('.json', [System.StringComparison]::OrdinalIgnoreCase)) {
        $lines = @('# Local validation summary')
        if ($Summary.ContainsKey('status')) { $lines += '', "- Status: $($Summary.status)" }
        if ($Summary.ContainsKey('runId')) { $lines += "- Run: $($Summary.runId)" }
        if ($Summary.ContainsKey('durationMs')) { $lines += "- Duration (ms): $($Summary.durationMs)" }
        if ($Summary.ContainsKey('traceId')) { $lines += "- Trace ID: $($Summary.traceId)" }
        if ($Summary.ContainsKey('pdfSha256')) { $lines += "- PDF SHA-256: $($Summary.pdfSha256)" }
        if ($Summary.ContainsKey('cleanup')) { $lines += "- Cleanup: $($Summary.cleanup)" }
        if ($Summary.ContainsKey('checks') -and $Summary.checks) {
            $lines += '', '## Checks', ''
            foreach ($check in $Summary.checks) { $lines += "- $($check.name): $($check.status)" }
        }
        $humanPath = Join-Path $directory ([System.IO.Path]::ChangeExtension($Name, '.md'))
        $lines | Set-Content -LiteralPath $humanPath -Encoding utf8
    }
    return $path
}

function Import-LiveAiEnvironment {
    param([Parameter(Mandatory = $true)][string]$Path)
    $values = Get-LocalEnvValues -Path $Path
    if ([string]::IsNullOrWhiteSpace($values['BAILIAN_API_KEY'])) { throw "Live AI requires BAILIAN_API_KEY in ignored local file: $Path" }
    $env:AI_PROVIDER = 'bailian'
    foreach ($pair in $values.GetEnumerator()) { Set-Item -Path "Env:$($pair.Key)" -Value $pair.Value }
}

function New-DisposableMySqlDatabase {
    param([string]$SourceSchema)
    Assert-LocalCommand mysql
    $requiresBaseline = $false
    if (-not [string]::IsNullOrWhiteSpace($SourceSchema)) {
        if ($SourceSchema -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe source schema name.' }
        Assert-LocalCommand mysqldump
        $sourceVersion = (& mysql --batch --skip-column-names $SourceSchema -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;").Trim()
        if ($LASTEXITCODE -ne 0 -or $sourceVersion -ne '19') {
            throw 'Database cloning currently supports only a source schema at Flyway V19.'
        }
    }
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 16)
    $schema = "intelligent_resume_gate_$suffix"
    $databaseUser = "ir_gate_$($suffix.Substring(0, 12))"
    $databasePassword = [guid]::NewGuid().ToString('N')
    if ($schema -notmatch '^intelligent_resume_gate_[a-f0-9]{16}$') { throw 'Unsafe temporary schema name.' }
    if ($databaseUser -notmatch '^ir_gate_[a-f0-9]{12}$') { throw 'Unsafe temporary database user name.' }

    $setupSql = @"
CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER '$databaseUser'@'%' IDENTIFIED BY '$databasePassword';
GRANT ALL PRIVILEGES ON ``$schema``.* TO '$databaseUser'@'%';
FLUSH PRIVILEGES;
"@
    $dumpPath = $null
    try {
        $setupSql | & mysql --batch --skip-column-names
        if ($LASTEXITCODE -ne 0) { throw 'Unable to create the disposable MySQL validation database.' }
        if (-not [string]::IsNullOrWhiteSpace($SourceSchema)) {
            $dumpPath = [System.IO.Path]::GetTempFileName()
            & mysqldump --no-data --triggers --column-statistics=0 "--result-file=$dumpPath" $SourceSchema
            if ($LASTEXITCODE -ne 0) { throw 'Unable to export the source schema.' }
            $importCommand = "mysql --binary-mode=1 --database=$schema < `"$dumpPath`""
            & cmd.exe /d /s /c $importCommand
            if ($LASTEXITCODE -ne 0) { throw 'Unable to import the source schema into the disposable database.' }
            $sourceTables = @(& mysql --batch --skip-column-names -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$SourceSchema' AND table_type='BASE TABLE' ORDER BY table_name;")
            if ($LASTEXITCODE -ne 0 -or $sourceTables.Count -eq 0) { throw 'Unable to enumerate source tables.' }
            $copyStatements = @('SET FOREIGN_KEY_CHECKS=0;')
            foreach ($table in $sourceTables) {
                if ($table -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe source table name.' }
                $copyStatements += "INSERT INTO ``$schema``.``$table`` SELECT * FROM ``$SourceSchema``.``$table``;"
            }
            $copyStatements += 'SET FOREIGN_KEY_CHECKS=1;'
            ($copyStatements -join [Environment]::NewLine) | & mysql --batch --skip-column-names
            if ($LASTEXITCODE -ne 0) { throw 'Unable to copy source data into the disposable database.' }
            foreach ($table in $sourceTables) {
                $counts = @(& mysql --batch --skip-column-names -e "SELECT (SELECT COUNT(*) FROM ``$SourceSchema``.``$table``), (SELECT COUNT(*) FROM ``$schema``.``$table``);")
                if ($LASTEXITCODE -ne 0 -or $counts.Count -ne 1 -or $counts[0] -notmatch '^(\d+)\s+(\d+)$' -or $Matches[1] -ne $Matches[2]) {
                    throw "Row-count verification failed for cloned table '$table'."
                }
            }
            $cloneInvariantSql = @"
SELECT IF(
    (SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1) = 19
    AND (SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'interview_record' AND column_name = 'round_no') = 'NO'
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'interview_record' AND index_name = 'uq_interview_record_session_round' AND non_unique = 0) = 2
    AND (SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'interview_session' AND column_name = 'job_description_id') = 'YES',
    'READY', 'INVALID');
"@
            $cloneInvariant = (& mysql --batch --skip-column-names $schema -e $cloneInvariantSql).Trim()
            if ($LASTEXITCODE -ne 0 -or $cloneInvariant -ne 'READY') {
                throw 'The cloned database does not match the required V19 schema invariants.'
            }
            & mysql --batch --skip-column-names $schema -e 'DROP TABLE flyway_schema_history;'
            if ($LASTEXITCODE -ne 0) { throw 'Unable to prepare the isolated clone for a V19 Flyway baseline.' }
            $requiresBaseline = $true
        }
    } catch {
        $cloneError = $_
        Remove-DisposableMySqlDatabase -Schema $schema -User $databaseUser
        throw $cloneError
    } finally {
        if ($dumpPath) { Remove-Item -LiteralPath $dumpPath -Force -ErrorAction SilentlyContinue }
    }
    return [pscustomobject]@{ Schema = $schema; User = $databaseUser; Password = $databasePassword; SourceSchema = $SourceSchema; RequiresBaseline = $requiresBaseline }
}

function Remove-DisposableMySqlDatabase {
    param(
        [Parameter(Mandatory = $true)][string]$Schema,
        [Parameter(Mandatory = $true)][string]$User
    )
    if ($Schema -notmatch '^intelligent_resume_gate_[a-f0-9]{16}$') { throw 'Refusing to remove an unsafe schema name.' }
    if ($User -notmatch '^ir_gate_[a-f0-9]{12}$') { throw 'Refusing to remove an unsafe database user name.' }
    $cleanupSql = @"
DROP DATABASE IF EXISTS ``$Schema``;
DROP USER IF EXISTS '$User'@'%';
FLUSH PRIVILEGES;
"@
    $cleanupSql | & mysql --batch --skip-column-names
    if ($LASTEXITCODE -ne 0) { throw 'Unable to remove the disposable MySQL validation database.' }
}
