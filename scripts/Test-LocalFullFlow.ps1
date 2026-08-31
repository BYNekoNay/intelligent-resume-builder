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
    $deadline = (Get-Date).AddSeconds(150)
    do { $task = Invoke-Api GET $Path $null $Token $null; if ($task.status -in @('SUCCESS', 'FAILED', 'CANCELLED')) { return $task }; Start-Sleep -Milliseconds 300 } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for task $Path"
}

function Test-PendingValue($Value) {
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Contains('_pending')) { return $true }
        foreach ($key in $Value.Keys) { if ((-not $key.StartsWith('_')) -and (Test-PendingValue $Value[$key])) { return $true } }
    } elseif ($null -ne $Value -and $Value -is [pscustomobject]) {
        if ($Value.PSObject.Properties.Name -contains '_pending') { return $true }
        foreach ($property in $Value.PSObject.Properties) { if ((-not $property.Name.StartsWith('_')) -and (Test-PendingValue $property.Value)) { return $true } }
    } elseif ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($item in $Value) { if (Test-PendingValue $item) { return $true } }
    }
    return $false
}

function Get-DraftConfirmationItems($Draft) {
    $items = @()
    foreach ($property in $Draft.PSObject.Properties) {
        if ($property.Name.StartsWith('_')) { continue }
        $value = $property.Value
        if ($value -is [System.Collections.IEnumerable] -and $value -isnot [string] -and $value -isnot [System.Collections.IDictionary]) {
            $index = 0
            foreach ($entry in $value) {
                $items += @{ outputPath = "$($property.Name)[$index]"; decision = $(if (Test-PendingValue $entry) { 'REJECT' } else { 'ACCEPT' }) }
                $index++
            }
        } elseif ($null -ne $value) {
            $items += @{ outputPath = $property.Name; decision = $(if (Test-PendingValue $value) { 'REJECT' } else { 'ACCEPT' }) }
        }
    }
    return ,$items
}

function Assert-ApiRejected {
    param([string]$Method, [string]$Path, $Body, [string]$Token, [int]$ExpectedStatus)
    $headers = @{}; if ($Token) { $headers.Authorization = "Bearer $Token" }
    $arguments = @{ Method = $Method; Uri = "$ApiBaseUrl$Path"; Headers = $headers; ContentType = 'application/json'; UseBasicParsing = $true; ErrorAction = 'Stop' }
    if ($null -ne $Body) { $arguments.Body = ($Body | ConvertTo-Json -Depth 12 -Compress) }
    try { Invoke-WebRequest @arguments | Out-Null; throw "Expected HTTP $ExpectedStatus from $Method $Path." } catch {
        $response = if ($_.Exception.PSObject.Properties.Match('Response').Count -gt 0) { $_.Exception.Response } else { $null }
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
    $workMaterial = Invoke-Api POST '/api/career-materials' @{ materialType = 'WORK_EXPERIENCE'; title = 'Synthetic backend delivery'; contentJson = @{ company = 'Example Systems'; position = 'Backend Engineer'; period = '2024-2026'; description = 'Delivered Java and Spring Boot services.' }; sourceText = 'Delivered Java and Spring Boot services with product and operations partners.'; usagePreference = 'PREFERRED' } $token $null
    $material = Invoke-Api POST '/api/career-materials' @{ materialType = 'PROJECT_EXPERIENCE'; title = 'Synthetic API project'; contentJson = @{ name = 'Synthetic API project'; role = 'Backend Engineer'; period = '2025'; description = 'Built a local validation API.' }; sourceText = 'Built a local validation API with Java and Spring Boot.'; usagePreference = 'PREFERRED' } $token $null
    $achievement = Invoke-Api POST '/api/career-materials' @{ materialType = 'ACHIEVEMENT'; title = 'Latency improvement'; contentJson = @{ relatedMaterialId = $material.id; scenario = 'Peak traffic'; action = 'Introduced cache and query profiling'; outcome = 'Improved API responsiveness'; period = '2025 Q2'; metricName = 'P99 latency'; metricDisplayMode = 'RANGE'; metricDisplayValue = 'reduced by about one third'; metricExactValue = '37.4%' }; usagePreference = 'PREFERRED' } $token $null
    $leadership = Invoke-Api POST '/api/career-materials' @{ materialType = 'LEADERSHIP_EXPERIENCE'; title = 'Cross-team delivery'; contentJson = @{ relatedMaterialId = $workMaterial.id; responsibilityScope = 'Led backend delivery planning'; collaborationTargets = 'Product, QA, and operations'; teamSize = '6 contributors'; crossFunctionalRelationship = 'Coordinated release readiness across teams'; keyDecision = 'Introduced weekly risk review'; result = 'Delivered the migration on schedule' }; usagePreference = 'NORMAL' } $token $null
    $skillEvidence = Invoke-Api POST '/api/career-materials' @{ materialType = 'SKILL_EVIDENCE'; title = 'Java service evidence'; contentJson = @{ skillName = 'Java'; category = 'Backend'; proficiency = 'Advanced'; yearsOfExperience = '5 years'; lastUsedAt = '2026-07'; relatedMaterialIds = @($workMaterial.id, $material.id); applicationDescription = 'Built and maintained Spring Boot APIs'; outcomeEvidence = 'Supported reliable production releases' }; usagePreference = 'NORMAL' } $token $null
    $job = Invoke-Api POST '/api/jobs' @{ title = 'Synthetic Backend Engineer'; companyName = 'Example Company'; jdText = 'Java and Spring Boot backend engineer.' } $token $null
    Add-Check 'career-material-and-jd' { if (-not $material.id -or -not $achievement.id -or -not $leadership.id -or -not $skillEvidence.id -or -not $job.id) { throw 'Failed to create synthetic materials or job.' } }
    Invoke-Api PUT '/api/personal-profile' @{ fullName = 'Synthetic Candidate'; email = "local-$runId@example.invalid"; phone = '13800000000'; location = 'Shanghai'; website = 'https://example.invalid'; profileSummary = 'Java backend engineer with Spring Boot project experience.'; targetRoleTitles = @('Java Backend Engineer'); targetSeniority = 'Senior'; targetIndustries = @('SaaS'); targetWorkPreferences = @('Shanghai', 'Hybrid'); careerPositioningSummary = 'Backend engineer focused on reliable services and cross-team delivery.' } $token $null | Out-Null
    Invoke-Api POST '/api/ai/consent' @{ policyVersion = 'v1.2.0'; providerCode = 'bailian'; taskScopes = @('JOB_MATERIAL_SELECTION', 'JOB_GENERATION'); dataCategories = @('CAREER_MATERIAL', 'JOB_DESCRIPTION', 'PERSONAL_PROFILE'); noticeHash = 'local-validation' } $token $null | Out-Null
    Add-Check 'ai-consent' { Invoke-Api GET '/api/ai/consent' $null $token $null | Out-Null }
    $selectionTask = Invoke-Api POST '/api/ai/select-materials-for-job' @{ jobDescriptionId = $job.id; includedMaterialIds = @($material.id, $achievement.id, $leadership.id, $skillEvidence.id); preferredMaterialIds = @(); excludedMaterialIds = @(); resumeTitle = 'Local validation resume' } $token @{ 'Idempotency-Key' = "selection-$runId" }
    $selectionTask = Wait-Task "/api/ai/tasks/$($selectionTask.id)" $token
    $summary.taskStates += @{ type = 'JOB_MATERIAL_SELECTION'; status = $selectionTask.status; retryCount = $selectionTask.retryCount }
    Add-Check 'material-selection' { if ($selectionTask.status -ne 'SUCCESS') { throw "Material selection ended as $($selectionTask.status)." } }
    $recommended = @($selectionTask.resultJson.recommended | ForEach-Object { $_.materialId })
    if ($recommended.Count -eq 0) { $recommended = @($selectionTask.resultJson.unselected | Select-Object -First 1 | ForEach-Object { $_.materialId }) }
    Add-Check 'material-selection-result' { if ($recommended.Count -eq 0 -or -not ($recommended -contains $achievement.id) -or -not ($recommended -contains $leadership.id) -or -not ($recommended -contains $skillEvidence.id)) { throw 'Material selection did not retain all required evidence materials.' } }
    $task = Invoke-Api POST "/api/ai/tasks/$($selectionTask.id)/confirm-materials" @{ taskUpdatedAt = $selectionTask.updatedAt; selectedMaterialIds = $recommended; forcedIncludedMaterialIds = @(); resumeTitle = 'Local validation resume' } $token @{ 'Idempotency-Key' = "confirm-materials-$runId" }
    $task = Wait-Task "/api/ai/tasks/$($task.id)" $token
    $summary.taskStates += @{ type = 'JOB_GENERATION'; status = $task.status; retryCount = $task.retryCount }
    Add-Check 'job-generation' { if ($task.status -ne 'SUCCESS') { throw "Job generation ended as $($task.status)." } }
    $items = Get-DraftConfirmationItems $task.resultJson.draftResumeJson
    Add-Check 'draft-confirmation-items' { if ($items.Count -eq 0) { throw 'Generated draft had no confirmable content.' } }
    $confirmed = Invoke-Api POST "/api/ai/tasks/$($task.id)/confirm" @{ taskUpdatedAt = $task.updatedAt; items = $items; additionalResumeJson = @{} } $token @{ 'Idempotency-Key' = "confirm-$runId" }
    Add-Check 'generation-confirmation' { if (-not $confirmed.resumeVersionId) { throw 'Confirmation did not create a resume version.' } }
    $match = Invoke-Api POST '/api/scoring/match' @{ resumeVersionId = $confirmed.resumeVersionId; jobDescriptionId = $job.id } $token $null
    Add-Check 'resume-scoring' { if (-not $match.matchResultId) { throw 'Scoring did not create a match result.' } }
    $communication = Invoke-Api POST '/api/communications/generate' @{ resumeVersionId = $confirmed.resumeVersionId; jobDescriptionId = $job.id; type = 'EMAIL' } $token $null
    Add-Check 'communication-draft' { if ([string]::IsNullOrWhiteSpace($communication.draft)) { throw 'Communication generation returned an empty body.' } }
    $application = Invoke-Api POST '/api/applications' @{ jobDescriptionId = $job.id; resumeVersionId = $confirmed.resumeVersionId; status = 'DRAFT'; coverLetterText = $null; emailBodyText = 'Synthetic local validation email body.'; openingMessageText = $null } $token $null
    Add-Check 'application-create' { if (-not $application.id) { throw 'Application creation did not return an ID.' } }
    $asset = Invoke-Api POST '/api/interview-answer-assets' @{ interviewRecordId = $null; questionText = 'What did you build?'; originalAnswerText = 'I built the synthetic local validation flow.'; suggestedAnswerText = $null; feedbackJson = @{ localValidation = $true } } $token $null
    Add-Check 'interview-asset' { if (-not $asset.id) { throw 'Interview answer asset was not created.' } }
    $other = Invoke-Api POST '/api/auth/register' @{ username = "isolation$runId"; email = "isolation-$runId@example.invalid"; password = $password } $null $null
    $otherToken = $other.accessToken
    Add-Check 'ownership-isolation' { Assert-ApiRejected GET "/api/resumes/$($confirmed.resumeId)" $null $otherToken 404 }
    Invoke-Api DELETE '/api/ai/consent' $null $token $null | Out-Null
    Add-Check 'withdrawn-consent-blocks-ai' { Assert-ApiRejected POST '/api/ai/select-materials-for-job' @{ jobDescriptionId = $job.id; includedMaterialIds = @($material.id); preferredMaterialIds = @(); excludedMaterialIds = @(); resumeTitle = 'Blocked after withdrawal' } $token 403 }
    Add-Check 'withdrawn-consent-keeps-non-ai-available' { Invoke-Api GET "/api/resumes/$($confirmed.resumeId)" $null $token $null | Out-Null }
    if ($VerifyPdfRecovery) { & (Join-Path $PSScriptRoot 'Invoke-LocalFault.ps1') -Action StopPdf }
    $export = Invoke-Api POST '/api/exports/pdf' @{ resumeVersionId = $confirmed.resumeVersionId; templateCode = 'classic' } $token $null
    $export = Wait-Task "/api/exports/tasks/$($export.taskId)" $token
    $summary.exportStates += @{ status = $export.status; template = $export.templateCode }
    if ($VerifyPdfRecovery) {
        Add-Check 'pdf-outage' { if ($export.status -ne 'FAILED') { throw "PDF outage did not fail the export task (received $($export.status))." } }
        & (Join-Path $PSScriptRoot 'Invoke-LocalFault.ps1') -Action StartPdf
        $export = Invoke-Api POST "/api/exports/tasks/$($export.taskId)/retry" $null $token $null
        $export = Wait-Task "/api/exports/tasks/$($export.taskId)" $token
        $summary.exportStates += @{ status = $export.status; template = $export.templateCode; retry = $true }
        Add-Check 'pdf-recovery-retry' { if ($export.status -ne 'SUCCESS') { throw "PDF retry ended as $($export.status)." } }
    }
    Add-Check 'pdf-export' {
        if ($export.status -ne 'SUCCESS') { throw "PDF export ended as $($export.status)." }
        Assert-ApiRejected GET "/api/exports/files/$($export.taskId)" $null $otherToken 404
        $file = Invoke-WebRequest -Uri "$ApiBaseUrl/api/exports/files/$($export.taskId)" -Headers @{ Authorization = "Bearer $token" } -UseBasicParsing
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
