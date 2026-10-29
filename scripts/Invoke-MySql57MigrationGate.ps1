# 本机 MySQL 5.7 迁移门禁（Windows / 本机已装 5.7 时使用）。
#
# 官方入口是 scripts/Invoke-PersistenceGate.sh：它在云服务器上跑两个口径
# （mysql84 = 生产口径 V1→V38 全新安装；mysql57 = 存量升级 V19→V38）。
# 本文件仅供「本机 3306 已有 5.7」的开发机做快速校验，且它假定本机有 mysql 客户端与管理员凭据。
#
# 注（2026-10-28）：本脚本已被上述入口取代，仅作追溯保留；其中的版本号口径不再跟随迁移推进。
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
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 5.7 V19-to-current upgrade gate failed.' }
    # 「被跳过」不等于「通过」：环境变量没生效时 JUnit 会跳过整类，mvn 仍返回 0，
    # 而 surefire 报告里是 tests=1 skipped=1。只看退出码会把「未验证」记成绿。
    $report = Join-Path $root 'server\target\surefire-reports\TEST-com.intelligentresume.database.MySql57MigrationLiveIT.xml'
    if (-not (Test-Path $report)) { throw "MySQL 5.7 gate surefire report missing: $report — the class did not run." }
    [xml]$reportXml = Get-Content $report
    $ranTests = [int]$reportXml.testsuite.tests
    $skippedTests = [int]$reportXml.testsuite.skipped
    if ($ranTests -eq 0 -or $skippedTests -ge $ranTests) {
        throw "MySQL 5.7 gate was skipped (tests=$ranTests skipped=$skippedTests) — that is NOT a pass."
    }
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
