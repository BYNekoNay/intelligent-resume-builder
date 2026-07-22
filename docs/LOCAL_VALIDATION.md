# Local full-flow validation

This guide validates the application on one machine only. It does not configure or contact any deployment environment.

## Safety rules

- Keep `.env` mock-only. Never put a Bailian key or `AI_PROVIDER=bailian` in that file.
- Store a live key only in ignored `.env.live-ai`; do not put it in screenshots, terminal transcripts, reports, or Git.
- All generated reports, logs, downloads, and browser artifacts belong in ignored `.local-validation/`.
- Use synthetic names, email addresses, career materials, and job descriptions only.

## Mock baseline

Prerequisites: a running MySQL server matching the `SPRING_DATASOURCE_*` values in root `.env`, Java 17, Maven, Node.js, and installed dependencies in `web` and `pdf-service`.

For an existing local MySQL installation, add these values to ignored root `.env` with your actual database and credentials (do not use the example password unless it is the password you configured):

```properties
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/intelligent_resume?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=your_local_mysql_user
SPRING_DATASOURCE_PASSWORD=your_local_mysql_password
```

The database user needs permission to create and migrate the `intelligent_resume` schema. Flyway runs automatically when the API starts.

```powershell
.\scripts\Start-LocalValidation.ps1 -Mode Mock -SkipMySql
.\scripts\Test-LocalFullFlow.ps1 -VerifyPdfRecovery
.\scripts\Stop-LocalValidation.ps1
```

The start script uses the already-running local MySQL instance, then starts PDF service on `3001`, API on `8080`, and web on `5173`. It explicitly targets the web process at `http://127.0.0.1:8080`, so stale developer-only `web/.env` ports do not affect this validation lane.
The full-flow script creates and deletes one synthetic user. It verifies authentication, resume/material/JD creation, consent, job generation and confirmation, scoring, editable communication/application data, interview-answer assets, cross-user isolation, and authorized PDF download. It also runs a local-only AI retry drill: a marked synthetic task fails once, then must recover through the normal retry endpoint. With `-VerifyPdfRecovery`, it additionally stops the PDF service, verifies a failed export, restarts the service, and verifies that retry succeeds.

Each run writes a redacted JSON report and a readable Markdown summary under `.local-validation/`. The allowed evidence is status, duration, synthetic run ID, trace ID, PDF SHA-256, and cleanup status; request bodies, credentials, cookies, and downloaded files are not written.

## Failure drill

With the mock baseline running, the full-flow command above already performs the PDF outage/retry drill. The following commands are useful for manual troubleshooting:

```powershell
.\scripts\Invoke-LocalFault.ps1 -Action StopPdf
.\scripts\Invoke-LocalFault.ps1 -Action StartPdf
```

The task state and retry outcome belong in the generated redacted summary, never in a tracked evidence folder.

## Live Bailian provider gate

Create ignored `.env.live-ai` containing `BAILIAN_API_KEY` and, optionally, `BAILIAN_MODELS`.
Do not add `AI_PROVIDER` to `.env`; the gate injects it only into its Maven child process.

```powershell
$env:BAILIAN_LIVE_TEST = 'true'
.\scripts\Invoke-LiveAiGate.ps1
Remove-Item Env:BAILIAN_LIVE_TEST
```

The gate uses synthetic prompts, validates structured responses, and prints field/count summaries rather than source content.

## Browser local-services smoke

Start the mock baseline first, then run:

```powershell
Set-Location web
npm run test:e2e:local
```

This command is opt-in and only accepts the documented loopback origin. Browser trace, video, and screenshots are disabled by default.
