#requires -Version 5.1
<#
  HireHub phase-3b verification: the three things still unproven.
    A) resume parsing over a REAL file (MinIO -> PDFBox -> DB -> ES)
    B) interview reminder REAL TTL+DLX delayed path (needs a FUTURE interview)
    C) WebSocket one-time ticket + handshake + message loop

  Usage:
    1) .\scripts\e2e-verify.ps1 -Fresh *>&1 | Tee-Object logs\e2e-setup.log   (sets up users/ids)
    2) .\scripts\phase3b-verify.ps1

  Pure ASCII by design: no literal Chinese in this file.
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_ = '123456'

# ---------- parse the setup log produced by e2e-verify.ps1 ----------
$setupLog = Join-Path $PSScriptRoot '..\logs\e2e-setup.log'
if (-not (Test-Path $setupLog)) { Write-Host "missing $setupLog - run e2e-verify.ps1 -Fresh first"; exit 2 }
$setupTxt = Get-Content $setupLog -Raw
$reAlice = [regex]::Match($setupTxt, 'alice \S+ \((alice\d+)\)')
$reBob   = [regex]::Match($setupTxt, 'bob \S+ \((bob\d+)\)')
$reIds   = [regex]::Match($setupTxt, 'IDS: companyId=(\d+) jobId=(\d+) resumeId=(\d+) deliveryId=(\d+) interviewId=(\d+)')
if (-not $reAlice.Success -or -not $reIds.Success) { Write-Host 'could not parse setup log'; exit 2 }
$AliceU = $reAlice.Groups[1].Value
$BobU   = $reBob.Groups[1].Value
$CompanyId  = [int]$reIds.Groups[1].Value
$JobId      = [int]$reIds.Groups[2].Value
$DeliveryId = [int]$reIds.Groups[4].Value
Write-Host ("setup: alice={0} bob={1} companyId={2} jobId={3} deliveryId={4}" -f $AliceU, $BobU, $CompanyId, $JobId, $DeliveryId)

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function Login([string]$u) { return (Api POST '/api/auth/login' -Body (Json @{ username = $u; password = $PWD_ })).Json.data }
$alice = Login $AliceU
$AliceTok = $alice.accessToken
$bob = Login $BobU
$BobTok = $bob.accessToken
Assert-Step 'setup: both users can log in' ([bool]$AliceTok -and [bool]$BobTok) ''

# =====================================================================
# A) resume parsing over a real file
# =====================================================================
Write-Host ''
Write-Host '########## A) resume parsing: real PDF + corrupt file ##########'

function New-MinimalPdf([string]$text) {
    $lines = $text -split "`n"
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("BT /F1 12 Tf 72 720 Td 14 TL`n")
    foreach ($l in $lines) { [void]$sb.Append('(' + ($l -replace '([()\\])', '\$1') + ") Tj T*`n") }
    [void]$sb.Append('ET')
    $content = $sb.ToString()
    $objs = @(
        '<</Type/Catalog/Pages 2 0 R>>',
        '<</Type/Pages/Kids[3 0 R]/Count 1>>',
        '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>',
        ("<</Length " + [System.Text.Encoding]::ASCII.GetByteCount($content) + ">>`nstream`n" + $content + "`nendstream"),
        '<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>'
    )
    $out = New-Object System.Text.StringBuilder
    [void]$out.Append("%PDF-1.4`n")
    $offsets = @()
    for ($i = 0; $i -lt $objs.Count; $i++) {
        $offsets += $out.Length
        [void]$out.Append(("{0} 0 obj`n{1}`nendobj`n" -f ($i + 1), $objs[$i]))
    }
    $xrefPos = $out.Length
    [void]$out.Append("xref`n0 " + ($objs.Count + 1) + "`n")
    [void]$out.Append("0000000000 65535 f `n")
    foreach ($o in $offsets) { [void]$out.Append(("{0:d10} 00000 n `n" -f $o)) }
    [void]$out.Append("trailer`n<</Size " + ($objs.Count + 1) + "/Root 1 0 R>>`nstartxref`n$xrefPos`n%%EOF`n")
    return [System.Text.Encoding]::ASCII.GetBytes($out.ToString())
}

function Upload-And-Confirm([int]$resumeId, [byte[]]$bytes) {
    $u = Api POST "/api/resume/$resumeId/upload-url" -Token $AliceTok
    if ($u.Json.code -ne 0) { return $false, "upload-url failed: $($u.Text)" }
    $url = $u.Json.data.uploadUrl
    $key = $u.Json.data.objectKey
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = 'PUT'
    $req.Timeout = 30000
    $req.ContentLength = $bytes.Length
    $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    try { $r = $req.GetResponse() } catch { return $false, "PUT to MinIO failed: $($_.Exception.Message)" }
    $code = [int]$r.StatusCode; $r.Close()
    if ($code -ne 200) { return $false, "MinIO PUT returned $code" }
    $c = Api POST "/api/resume/$resumeId/confirm-upload" -Token $AliceTok -Body (Json @{ objectKey = $key })
    if ($c.Json.code -ne 0) { return $false, "confirm-upload failed: $($c.Text)" }
    return $true, $key
}

function Wait-ParseStatus([int]$resumeId, [int]$want, [int]$maxSec) {
    for ($i = 0; $i -lt $maxSec; $i += 2) {
        Start-Sleep -Seconds 2
        $cur = Api GET "/api/resume/$resumeId" -Token $AliceTok
        if ($cur.Json.data -and [int]$cur.Json.data.parseStatus -eq $want) { return $want }
    }
    $cur = Api GET "/api/resume/$resumeId" -Token $AliceTok
    if ($cur.Json.data) { return [int]$cur.Json.data.parseStatus }
    return -1
}

# A1: real PDF. NOTE: POST /api/resume REQUIRES "name" (server-side @NotBlank),
# so omitting it yields 400 and resumeId=0 -- asserted here so a bad body is loud.
$crA = Api POST '/api/resume' -Token $AliceTok -Body (Json @{ title = 'p3 parse ok'; name = 'Tester A'; expectPosition = 'Java'; status = 0 })
Assert-Step 'A: create resume for parsing' ($crA.Json.code -eq 0) $crA.Text
$ResumeA = [int]$crA.Json.data
$pdfText = "Phone: 13711112222`nEmail: lixm@example.com`nSkills: Java, Spring Boot, MySQL, Redis, Docker`n5 years experience"
$pdfBytes = New-MinimalPdf $pdfText
Assert-Step 'A: PDF built and non-empty' ($pdfBytes.Length -gt 200) ("bytes={0}" -f $pdfBytes.Length)
$upA, $keyA = Upload-And-Confirm $ResumeA $pdfBytes
Assert-Step 'A: presigned PUT + confirm-upload accepted' ($upA -eq $true) ([string]$keyA)

$statusA = Wait-ParseStatus $ResumeA 2 40
$afterA = Api GET "/api/resume/$ResumeA" -Token $AliceTok
$prA = Api GET "/api/resume/$ResumeA/parse-result" -Token $AliceTok
Assert-Step 'A: parse succeeded (parseStatus=2)' ($statusA -eq 2) ("parseStatus={0}" -f $statusA)
$skillsA = ''
if ($prA.Json.data) { $skillsA = [string]$prA.Json.data.skills }
Assert-Step 'A: skills extracted from PDF text' ($skillsA -match 'Java') ("skills={0}" -f $skillsA)
Assert-Step 'A: phone backfilled from PDF (was empty)' ($afterA.Json.data.phone -eq '13711112222') ("phone={0}" -f $afterA.Json.data.phone)
Assert-Step 'A: email backfilled from PDF' ($afterA.Json.data.email -eq 'lixm@example.com') ("email={0}" -f $afterA.Json.data.email)

# A2: corrupt bytes behind a .pdf key -> must fail loudly, not silently succeed
$crB = Api POST '/api/resume' -Token $AliceTok -Body (Json @{ title = 'p3 parse bad'; name = 'Tester B'; status = 0 })
Assert-Step 'A: create resume for corrupt file' ($crB.Json.code -eq 0) $crB.Text
$ResumeB = [int]$crB.Json.data
$garbage = New-Object byte[] 512
for ($i = 0; $i -lt 512; $i++) { $garbage[$i] = [byte](($i * 37) % 256) }
$upB, $keyB = Upload-And-Confirm $ResumeB $garbage
Assert-Step 'A: corrupt file accepted by MinIO (upload layer)' ($upB -eq $true) ([string]$keyB)
$statusB = Wait-ParseStatus $ResumeB 3 30
$prB = Api GET "/api/resume/$ResumeB/parse-result" -Token $AliceTok
Assert-Step 'A: corrupt file ends as parseStatus=3 (failed loudly)' ($statusB -eq 3) ("parseStatus={0}" -f $statusB)
$errB = ''
if ($prB.Json.data) { $errB = [string]$prB.Json.data.parseError }
Assert-Step 'A: failure carries a human-readable reason' ($errB.Length -gt 5) ("parseError={0}" -f $errB)

# =====================================================================
# B) interview reminder: REAL TTL + DLX delayed path
# =====================================================================
Write-Host ''
Write-Host '########## B) interview reminder TTL+DLX (future interview) ##########'
$future = (Get-Date).AddMinutes(31).ToString('yyyy-MM-ddTHH:mm:ss')
$ni = Api POST '/api/interview' -Token $BobTok -Body (Json @{ deliveryId = $DeliveryId; interviewTime = $future; interviewType = 'ONLINE'; addressOrLink = 'https://meet.example.com/p3'; interviewerName = 'HR' })
$NewIvId = [int]$ni.Json.data
Assert-Step 'B: HR creates a FUTURE interview (+31min)' ($ni.Json.code -eq 0 -and $NewIvId -gt 0) ("interviewId={0} time={1}" -f $NewIvId, $future)

Start-Sleep -Seconds 8
$n1 = Api GET '/api/notification/list' -Token $AliceTok
$cnt1 = @($n1.Json.data | Where-Object { $_.content -match "$NewIvId" }).Count
Assert-Step 'B: 1-day tier fired at once (its remind time already passed)' ($cnt1 -ge 1) ("matched={0} total={1}" -f $cnt1, @($n1.Json.data).Count)

Write-Host 'B: 30-min tier now sits in the delay queue with a REAL ~60s TTL; waiting 85s...'
Start-Sleep -Seconds 85
$n2 = Api GET '/api/notification/list' -Token $AliceTok
$matched = @($n2.Json.data | Where-Object { $_.content -match "$NewIvId" })
Assert-Step 'B: after TTL expiry BOTH tiers delivered (TTL+DLX works)' ($matched.Count -ge 2) ("delivered={0} total={1}" -f $matched.Count, @($n2.Json.data).Count)
foreach ($m in $matched) { Write-Host ("    -> " + $m.content) }

# =====================================================================
# C) WebSocket: one-time ticket + handshake + message loop
# =====================================================================
Write-Host ''
Write-Host '########## C) WebSocket ##########'
$tk = Api POST '/api/notification/ws-ticket' -Token $AliceTok
$ticket = $tk.Json.data
Assert-Step 'C: ws-ticket issued' ($tk.Json.code -eq 0 -and ([string]$ticket).Length -ge 16) ("ticketLen={0}" -f ([string]$ticket).Length)

$CT = [System.Threading.CancellationToken]::None

# Reads frames until one matches $pattern, SKIPPING the server heartbeat: the
# handler pushes {"type":"PING"} every 30s, so a single read is flaky.
function Ws-ReadUntil($ws, [string]$pattern, [int]$totalMs) {
    $buf = New-Object byte[] 16384
    $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(, $buf)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $totalMs) {
        $remain = $totalMs - [int]$sw.ElapsedMilliseconds
        if ($remain -lt 1000) { $remain = 1000 }
        $frame = ''
        try {
            $t = $ws.ReceiveAsync($seg, $CT)
            if ($t.Wait($remain)) { $frame = [System.Text.Encoding]::UTF8.GetString($buf, 0, $t.Result.Count) } else { break }
        } catch { break }
        if ($frame -match $pattern) { return $frame }
        if ($frame -match 'PING') { continue }
    }
    return ''
}

function Ws-Connect([string]$q) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ok = $false
    try { $ok = $ws.ConnectAsync([Uri]("ws://127.0.0.1:9000/ws/notification?$q"), $CT).Wait(8000) } catch { $ok = $false }
    return @{ Connected = $ok; Socket = $ws }
}

$good = Ws-Connect "ticket=$ticket"
Assert-Step 'C: handshake with valid ticket succeeds' ($good.Connected -eq $true) ("state={0}" -f $good.Socket.State)
$hello = Ws-ReadUntil $good.Socket 'CONNECTED' 8000
Assert-Step 'C: server sends CONNECTED frame' ($hello -match 'CONNECTED') ("frame={0}" -f $hello)

if ($good.Socket) {
    $ws = $good.Socket
    $ping = [System.Text.Encoding]::UTF8.GetBytes('{"type":"PING"}')
    $null = $ws.SendAsync((New-Object 'System.ArraySegment[byte]' -ArgumentList @(, $ping)), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $CT).Wait(5000)
    $pong = Ws-ReadUntil $ws 'PONG' 8000
    Assert-Step 'C: PING gets PONG (handler processes client frames)' ($pong -match 'PONG') ("frame={0}" -f $pong)

    # Trigger a notification WHILE connected: a new FUTURE interview fires its
    # 1-day tier immediately, lands in the notification table, and must be pushed.
    # (Do NOT reuse the e2e delivery: that run ends it in the terminal CANCELLED
    # state, so any further event would be rejected as an illegal transition.)
    $f2 = (Get-Date).AddMinutes(31).ToString('yyyy-MM-ddTHH:mm:ss')
    $null = Api POST '/api/interview' -Token $BobTok -Body (Json @{ deliveryId = $DeliveryId; interviewTime = $f2; interviewType = 'ONLINE' })
    $push = Ws-ReadUntil $ws 'NOTIFICATION' 25000
    Assert-Step 'C: live push on new notification' ($push -match 'NOTIFICATION') ("frame={0}" -f $push)
    $null = $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', $CT).Wait(3000)
}

$bad = Ws-Connect 'ticket=deadbeefdeadbeefdeadbeefdeadbeef'
$badHello = Ws-ReadUntil $bad.Socket 'CONNECTED' 4000
Assert-Step 'C: invalid ticket gets no CONNECTED frame' ($badHello -notmatch 'CONNECTED') ("frame={0}" -f $badHello)
try { $bad.Socket.Dispose() } catch { }

$reuse = Ws-Connect "ticket=$ticket"
$reuseHello = Ws-ReadUntil $reuse.Socket 'CONNECTED' 4000
Assert-Step 'C: ticket is one-time (reuse gets no CONNECTED)' ($reuseHello -notmatch 'CONNECTED') ("frame={0}" -f $reuseHello)
try { $reuse.Socket.Dispose() } catch { }
if ($good.Socket) { try { $good.Socket.Dispose() } catch { } }

Write-Host ''
Show-Summary
