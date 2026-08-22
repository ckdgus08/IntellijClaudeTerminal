param([string]$EventName)

# Windows counterpart to status-hook.sh. ConvertFrom/To-Json keeps prompts, paths and any
# future Codex hook fields valid without depending on their textual formatting.
$raw = [Console]::In.ReadToEnd()
try { $payload = $raw | ConvertFrom-Json } catch { exit 0 }

$sessionId = if ($payload.session_id) { [string]$payload.session_id } else { [string]$payload.sessionId }
if (-not $EventName) { $EventName = [string]$payload.hook_event_name }
if ($sessionId -notmatch '^[A-Za-z0-9._-]{1,128}$' -or $EventName -notmatch '^[A-Za-z]+$') { exit 0 }

$termSessionId = [string]$env:TERM_SESSION_ID
if ($termSessionId -notmatch '^[A-Za-z0-9._-]{1,128}$') { $termSessionId = '' }

$codexPid = 0
$parentPid = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID" -ErrorAction SilentlyContinue).ParentProcessId
for ($depth = 0; $depth -lt 8 -and $parentPid -gt 1; $depth++) {
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$parentPid" -ErrorAction SilentlyContinue
    if (-not $proc) { break }
    if (($proc.Name -match 'codex') -or ($proc.CommandLine -match 'codex')) {
        $codexPid = [long]$parentPid
        break
    }
    $parentPid = $proc.ParentProcessId
}

$statusDir = Join-Path $HOME '.codex\rider-agent-tabs\status'
New-Item -ItemType Directory -Force -Path $statusDir | Out-Null
$record = [ordered]@{
    event = $EventName
    termSessionId = $termSessionId
    ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    pid = $codexPid
    payload = $payload
}
$json = $record | ConvertTo-Json -Depth 32 -Compress

function Write-Atomic([string]$target) {
    $tmp = "$target.$PID.tmp"
    [IO.File]::WriteAllText($tmp, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -Force $tmp $target
}

Write-Atomic (Join-Path $statusDir "$sessionId.json")
if ($EventName -eq 'UserPromptSubmit') {
    $promptFile = Join-Path $statusDir "prompt-$sessionId.json"
    if (-not (Test-Path $promptFile)) { Write-Atomic $promptFile }
}
if ($termSessionId) { Write-Atomic (Join-Path $statusDir "termsess-$termSessionId.json") }
exit 0
