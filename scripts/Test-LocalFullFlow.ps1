[CmdletBinding()]
param([string]$ApiBaseUrl = 'http://127.0.0.1:8080', [switch]$VerifyPdfRecovery)

. (Join-Path $PSScriptRoot 'lib\LocalValidationHelpers.ps1')

Assert-LocalValidationDirectoryIgnored
$runId = [guid]::NewGuid().ToString('N').Substring(0, 10)
$started = Get-Date
$summary = @{ runId = $runId; startedAt = $started.ToUniversalTime().ToString('o'); api = $ApiBaseUrl; checks = @(); taskStates = @(); exportStates = @(); status = 'FAILED'; cleanup = 'NOT_ATTEMPTED' }
$token = $null
$otherToken = $null

function Add-Check([string]$Name, [scriptblock]$Action) {
    try { & $Action; $summary.checks += @{ name = $Name; status = 'PASS' } } catch { $summary.checks += @{ name = $Name; status = 'FAIL'; error = $_.Exception.Message }; throw }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token, [hashtable]$ExtraHeaders)
    $headers = @{}; if ($Token) { $headers.Authorization = "Bearer $Token" }; if ($ExtraHeaders) { $ExtraHeaders.Keys | ForEach-Object { $headers[$_] = $ExtraHeaders[$_] } }
    $arguments = @{ Method = $Method; Uri = "$ApiBaseUrl$Path"; Headers = $headers; ContentType = 'application/json'; UseBasicParsing = $true; ErrorAction = 'Stop' }
    if ($null -ne $Body) { $arguments.Body = ($Body | ConvertTo-Json -Depth 12 -Compress) }
    try { $result = Invoke-WebRequest @arguments } catch {
        $response = $_.Exception.Response
        if ($response) {
            $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
            try { $errorPayload = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            throw "API $Method $Path failed with HTTP $([int]$response.StatusCode), code $($errorPayload.code)."
        }
        throw
    }
    if ($result.Headers['X-Trace-Id']) { $summary.traceId = $result.Headers['X-Trace-Id'] }
    return ($result.Content | ConvertFrom-Json).data
}

function Wait-Task([string]$Path, [string]$Token) {
    $deadline = (Get-Date).AddSeconds(30)
    do { $task = Invoke-Api GET $Path $null $Token $null; if ($task.status -in @('SUCCESS', 'FAILED', 'CANCELLED')) { return $task }; Start-Sleep -Milliseconds 300 } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for task $Path"
}

function Assert-ApiRejected {
    param([string]$Method, [string]$Path, $Body, [string]$Token, [int]$ExpectedStatus)
    $headers = @{}; if ($Token) { $headers.Authorization = "Bearer $Token" }
    $arguments = @{ Method = $Method; Uri = "$ApiBaseUrl$Path"; Headers = $headers; ContentType = 'application/json'; UseBasicParsing = $true; ErrorAction = 'Stop' }
    if ($null -ne $Body) { $arguments.Body = ($Body | ConvertTo-Json -Depth 12 -Compress) }
    try { Invoke-WebRequest @arguments | Out-Null; throw "Expected HTTP $ExpectedStatus from $Method $Path." } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw 'Expected API rejection was not received.' }
        if ([int]$response.StatusCode -ne $ExpectedStatus) { throw "Expected HTTP $ExpectedStatus but received $([int]$response.StatusCode)." }
    }
}

try {
    Add-Check 'api-health' { if (-not (Test-LocalHttpEndpoint "$ApiBaseUrl/api/system/health")) { throw 'API health endpoint is unavailable.' } }
    $password = "LocalRun-$runId!"
    $registered = Invoke-Api POST '/api/auth/register' @{ username = "local$runId"; email = "local-$runId@example.invalid"; password = $password } $null $null
    $token = $registered.accessToken
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'Registration did not return an access token.' }
    Add-Check 'authentication' { Invoke-Api GET '/api/auth/me' $null $token $null | Out-Null }
    $resume = Invoke-Api POST '/api/resumes' @{ title = 'Local validation resume'; resumeJson = @{ basics = @{ name = 'Synthetic Candidate' }; work = @(); education = @(); skills = @(); projects = @() } } $token $null
    $material = Invoke-Api POST '/api/career-materials' @{ materialType = 'PROJECT_EXPERIENCE'; title = 'Synthetic API project'; contentJson = @{ name = 'Synthetic API project'; description = 'Built a local validation API.' }; sourceText = 'Built a local validation API with Java and Spring Boot.'; usagePreference = 'PREFERRED' } $token $null
    $job = Invoke-Api POST '/api/jobs' @{ title = 'Synthetic Backend Engineer'; companyName = 'Example Company'; jdText = 'Java and Spring Boot backend engineer.' } $token $null
    Add-Check 'career-material-and-jd' { if (-not $material.id -or -not $job.id) { throw 'Failed to create synthetic material or job.' } }
    Invoke-Api POST '/api/ai/consent' @{ policyVersion = 'local-v1'; providerCode = 'mock'; taskScopes = @('JOB_GENERATION'); dataCategories = @('CAREER_MATERIAL', 'JOB_DESCRIPTION'); noticeHash = 'local-validation' } $token $null | Out-Null
    Add-Check 'ai-consent' { Invoke-Api GET '/api/ai/consent' $null $token $null | Out-Null }
    $task = Invoke-Api POST '/api/ai/tasks' @{ targetResumeId = $resume.id; jobDescriptionId = $job.id; includedMaterialIds = @($material.id); preferredMaterialIds = @(); excludedMaterialIds = @(); additionalInput = @{} } $token @{ 'Idempotency-Key' = "local-$runId" }
    $task = Wait-Task "/api/ai/tasks/$($task.id)" $token
    $summary.taskStates += @{ type = 'JOB_GENERATION'; status = $task.status; retryCount = $task.retryCount }
    Add-Check 'job-generation' { if ($task.status -ne 'SUCCESS') { throw "Job generation ended as $($task.status)." } }
    $retryTask = Invoke-Api POST '/api/ai/tasks' @{ targetResumeId = $resume.id; jobDescriptionId = $job.id; includedMaterialIds = @($material.id); preferredMaterialIds = @(); excludedMaterialIds = @(); additionalInput = @{ localValidationFailOnce = $true } } $token @{ 'Idempotency-Key' = "retry-$runId" }
    $retryTask = Wait-Task "/api/ai/tasks/$($retryTask.id)" $token
    $summary.taskStates += @{ type = 'JOB_GENERATION_RETRY_DRILL'; status = $retryTask.status; retryCount = $retryTask.retryCount }
    Add-Check 'ai-outage' { if ($retryTask.status -ne 'FAILED') { throw "AI outage did not fail the task (received $($retryTask.status))." } }
    $retryTask = Invoke-Api POST "/api/ai/tasks/$($retryTask.id)/retry" $null $token $null
    $retryTask = Wait-Task "/api/ai/tasks/$($retryTask.id)" $token
    $summary.taskStates += @{ type = 'JOB_GENERATION_RETRY_DRILL'; status = $retryTask.status; retryCount = $retryTask.retryCount; retry = $true }
    Add-Check 'ai-recovery-retry' { if ($retryTask.status -ne 'SUCCESS' -or $retryTask.retryCount -ne 1) { throw 'AI retry did not recover the task exactly once.' } }
    $item = @{ outputPath = '/projects/0'; decision = 'ACCEPT'; editedValue = $null }
    $confirmed = Invoke-Api POST "/api/ai/tasks/$($task.id)/confirm" @{ taskUpdatedAt = $task.updatedAt; items = @($item); additionalResumeJson = @{} } $token @{ 'Idempotency-Key' = "confirm-$runId" }
    Add-Check 'generation-confirmation' { if (-not $confirmed.resumeVersionId) { throw 'Confirmation did not create a resume version.' } }
    $match = Invoke-Api POST '/api/scoring/match' @{ resumeVersionId = $confirmed.resumeVersionId; jobDescriptionId = $job.id } $token $null
    Add-Check 'resume-scoring' { if (-not $match.matchResultId) { throw 'Scoring did not create a match result.' } }
    $communication = Invoke-Api POST '/api/communications/generate' @{ resumeVersionId = $confirmed.resumeVersionId; jobDescriptionId = $job.id; type = 'EMAIL' } $token $null
    Add-Check 'communication-draft' { if ([string]::IsNullOrWhiteSpace($communication.draft)) { throw 'Communication generation returned an empty draft.' } }
    $applicationBody = 'Synthetic local validation email body.'
    $application = Invoke-Api POST '/api/applications' @{ jobDescriptionId = $job.id; resumeVersionId = $confirmed.resumeVersionId; status = 'DRAFT'; coverLetterText = $null; emailBodyText = $applicationBody; openingMessageText = $null } $token $null
    Add-Check 'application-create' { if (-not $application.id -or $application.emailBodyText -ne $applicationBody) { throw 'Application did not retain its editable synthetic draft.' } }
    $asset = Invoke-Api POST '/api/interview-answer-assets' @{ interviewRecordId = $null; questionText = 'What did you build?'; originalAnswerText = 'I built the synthetic local validation flow.'; suggestedAnswerText = $null; feedbackJson = @{ localValidation = $true } } $token $null
    Add-Check 'interview-asset' { if (-not $asset.id) { throw 'Interview answer asset was not created.' } }
    $other = Invoke-Api POST '/api/auth/register' @{ username = "isolation$runId"; email = "isolation-$runId@example.invalid"; password = $password } $null $null
    $otherToken = $other.accessToken
    Add-Check 'ownership-isolation' { Assert-ApiRejected GET "/api/resumes/$($resume.id)" $null $otherToken 404 }
    Invoke-Api DELETE '/api/ai/consent' $null $token $null | Out-Null
    Add-Check 'withdrawn-consent-blocks-ai' { Assert-ApiRejected POST '/api/communications/generate' @{ resumeVersionId = $confirmed.resumeVersionId; jobDescriptionId = $job.id; type = 'EMAIL' } $token 403 }
    Add-Check 'withdrawn-consent-keeps-non-ai-available' { Invoke-Api GET "/api/resumes/$($resume.id)" $null $token $null | Out-Null }
    if ($VerifyPdfRecovery) { & (Join-Path $PSScriptRoot 'Invoke-LocalFault.ps1') -Action StopPdf }
    $export = Invoke-Api POST '/api/exports/pdf' @{ resumeVersionId = $confirmed.resumeVersionId; templateCode = 'classic' } $token $null
    $export = Wait-Task "/api/exports/tasks/$($export.id)" $token
    $summary.exportStates += @{ status = $export.status; template = $export.templateCode }
    if ($VerifyPdfRecovery) {
        Add-Check 'pdf-outage' { if ($export.status -ne 'FAILED') { throw "PDF outage did not fail the export task (received $($export.status))." } }
        & (Join-Path $PSScriptRoot 'Invoke-LocalFault.ps1') -Action StartPdf
        $export = Invoke-Api POST "/api/exports/tasks/$($export.id)/retry" $null $token $null
        $export = Wait-Task "/api/exports/tasks/$($export.id)" $token
        $summary.exportStates += @{ status = $export.status; template = $export.templateCode; retry = $true }
        Add-Check 'pdf-recovery-retry' { if ($export.status -ne 'SUCCESS') { throw "PDF retry ended as $($export.status)." } }
    }
    Add-Check 'pdf-export' {
        if ($export.status -ne 'SUCCESS') { throw "PDF export ended as $($export.status)." }
        Assert-ApiRejected GET "/api/exports/files/$($export.id)" $null $otherToken 404
        $file = Invoke-WebRequest -Uri "$ApiBaseUrl/api/exports/files/$($export.id)" -Headers @{ Authorization = "Bearer $token" } -UseBasicParsing
        $signature = [System.Text.Encoding]::ASCII.GetString([byte[]]$file.Content, 0, [Math]::Min(4, $file.Content.Length))
        if ($signature -ne '%PDF') { throw 'Downloaded file is not a PDF.' }
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try { $summary.pdfSha256 = ([System.BitConverter]::ToString($sha256.ComputeHash([byte[]]$file.Content))).Replace('-', '').ToLowerInvariant() } finally { $sha256.Dispose() }
    }
    $summary.status = 'PASS'
} finally {
    if ($otherToken) { try { Invoke-Api DELETE '/api/auth/me' $null $otherToken $null | Out-Null } catch {} }
    if ($token) {
        try { Invoke-Api DELETE '/api/auth/me' $null $token $null | Out-Null; $summary.cleanup = 'ACCOUNT_DELETED' } catch { $summary.cleanup = 'ACCOUNT_DELETE_FAILED' }
    }
    $summary.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $summary.durationMs = [int]((Get-Date) - $started).TotalMilliseconds
    Write-LocalValidationSummary -Name "full-flow-$runId.json" -Summary $summary | Write-Output
}

if ($summary.status -ne 'PASS') { exit 1 }
