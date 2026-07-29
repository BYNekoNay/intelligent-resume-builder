[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')

Assert-LocalCommand java
Assert-LocalCommand mvn
Assert-LocalCommand mysql
$root = Get-LocalValidationRoot
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 16)
$schema = "intelligent_resume_gate_$suffix"
$databaseUser = "ir_gate_$($suffix.Substring(0, 12))"
$databasePassword = [guid]::NewGuid().ToString('N')
if ($schema -notmatch '^intelligent_resume_gate_[a-f0-9]{16}$') { throw 'Unsafe temporary schema name.' }
if ($databaseUser -notmatch '^ir_gate_[a-f0-9]{12}$') { throw 'Unsafe temporary database user name.' }

$pushedLocation = $false
try {
    $setupSql = @"
CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER '$databaseUser'@'%' IDENTIFIED BY '$databasePassword';
GRANT ALL PRIVILEGES ON ``$schema``.* TO '$databaseUser'@'%';
FLUSH PRIVILEGES;
"@
    $setupSql | & mysql --batch --skip-column-names
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the disposable MySQL 5.7 upgrade gate schema.' }

    $env:MYSQL57_LIVE_TEST = 'true'
    # The disposable local account may use MySQL 8's caching_sha2_password authentication.
    # This remains limited to the loopback, short-lived validation connection.
    $env:MYSQL57_JDBC_URL = "jdbc:mysql://127.0.0.1:3306/$schema`?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    $env:MYSQL57_USER = $databaseUser
    $env:MYSQL57_PASSWORD = $databasePassword

    Push-Location (Join-Path $root 'server')
    $pushedLocation = $true
    & mvn test '-Dtest=MySql57MigrationLiveIT'
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 5.7 V19-to-V22 upgrade gate failed.' }
} finally {
    if ($pushedLocation) { Pop-Location }
    $cleanupSql = @"
DROP DATABASE IF EXISTS ``$schema``;
DROP USER IF EXISTS '$databaseUser'@'%';
FLUSH PRIVILEGES;
"@
    $cleanupSql | & mysql --batch --skip-column-names
    Remove-Item Env:MYSQL57_LIVE_TEST -ErrorAction SilentlyContinue
    Remove-Item Env:MYSQL57_JDBC_URL -ErrorAction SilentlyContinue
    Remove-Item Env:MYSQL57_USER -ErrorAction SilentlyContinue
    Remove-Item Env:MYSQL57_PASSWORD -ErrorAction SilentlyContinue
}
