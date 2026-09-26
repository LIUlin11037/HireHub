#requires -Version 5.1
<#
  phase3e-verify.ps1 -- close the 10 endpoints that api-coverage.ps1 reports as
  never called by any script.

    A) GET  /api/company/search                  (company_index, D-22)
    B) POST /api/company/{id}/invite-code + POST /api/company/join
    C) POST /api/company/authorize               (D-24 third layer)
    D) POST /api/job/{id}/offline                (HR self-offline, vs admin force-offline)
    E) PUT  /api/notification/{id}/read + PUT /api/notification/read-all
    F) POST /api/resume/{id}/refresh-index
    G) GET  /swagger-ui.html                     (+ /v3/api-docs)
    H) GET  /api/job/search/nl                   (D-29) -- LLM branch if a key is present,
                                                 otherwise only the degradation branch

  Usage:
    .\scripts\e2e-verify.ps1 -Fresh *>&1 | Tee-Object logs\e2e-setup.log
    .\scripts\phase3e-verify.ps1

  Needs `docker` only for section H (to read the job container's env + logs).

  Pure ASCII by design (see docs/pitfalls #9); no Chinese literals in this file.
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_ = '123456ab'
$ID_CARD_OK = '110101199003078515'
$CITY_HZ = -join ([char]0x676D, [char]0x5DDE)
$NOTIFY_BASE = 'http://localhost:9007'

$setupLog = Join-Path $PSScriptRoot '..\logs\e2e-setup.log'
if (-not (Test-Path $setupLog)) { Write-Host "missing $setupLog - run e2e-verify.ps1 -Fresh first"; exit 2 }
$setupTxt = Get-Content $setupLog -Raw
$reAlice = [regex]::Match($setupTxt, 'alice \S+ \((alice\d+)\)')
$reBob   = [regex]::Match($setupTxt, 'bob \S+ \((bob\d+)\)')
$reIds   = [regex]::Match($setupTxt, 'IDS: companyId=(\d+) jobId=(\d+) resumeId=(\d+) deliveryId=(\d+) interviewId=(\d+)')
if (-not $reAlice.Success -or -not $reBob.Success -or -not $reIds.Success) { Write-Host 'could not parse setup log'; exit 2 }
$AliceU = $reAlice.Groups[1].Value
$BobU   = $reBob.Groups[1].Value
$CompanyId = [int]$reIds.Groups[1].Value
$JobId     = [int]$reIds.Groups[2].Value
$ResumeId  = [int]$reIds.Groups[3].Value
$DeliveryId = [int]$reIds.Groups[4].Value
Write-Host ("setup: alice={0} bob={1} companyId={2} jobId={3} resumeId={4}" -f $AliceU, $BobU, $CompanyId, $JobId, $ResumeId)

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function Login([string]$u) { return (Api POST '/api/auth/login' -Body (Json @{ username = $u; password = $PWD_ })).Json.data }

# Call an absolute URL (Api builds "$script:GW$Path", so blank GW + full URL works)
function ApiAbs([string]$Method, [string]$Url, [string]$Token, [string]$Body) {
    $save = $script:GW
    $script:GW = ''
    try {
        if ($Token -and $Body) { return (Api $Method $Url -Token $Token -Body $Body) }
        if ($Token) { return (Api $Method $Url -Token $Token) }
        if ($Body) { return (Api $Method $Url -Body $Body) }
        return (Api $Method $Url)
    } finally { $script:GW = $save }
}

$AliceTok = (Login $AliceU).accessToken
$BobTok   = (Login $BobU).accessToken
Assert-Step 'setup: both users log in' ([bool]$AliceTok -and [bool]$BobTok) ''

$sfx = Get-Random -Minimum 1000 -Maximum 9999
function New-CreditCode {
    $A = '0123456789ABCDEFGHJKLMNPQRTUWXY'
    $W = @(1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)
    $b = ('91330100' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()).Substring(0, 17)
    $s = 0
    for ($i = 0; $i -lt 17; $i++) { $s += $A.IndexOf($b[$i]) * $W[$i] }
    return $b + $A[(31 - $s % 31) % 31]
}
function New-User([string]$user, [string]$phone) {
    Api POST '/api/auth/register' -Body (Json @{ username = $user; password = $PWD_; phone = $phone }) | Out-Null
    $d = Login $user
    Api POST '/api/auth/real-name' -Token $d.accessToken -Body (Json @{ realName = 'Tester E'; idCard = $ID_CARD_OK }) | Out-Null
    return [pscustomobject]@{ Token = $d.accessToken; UserId = $d.userId }
}

# ------------------------------------------------------------------
# Setup: one EXTRA company that stays UNVERIFIED (used by A-negative and C)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## Setup: an unverified company (fixture for A-negative / C) ##########'
$PendU = "p3ep$sfx"
$pendUser = New-User $PendU ('138' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString())
$PendTok = $pendUser.Token
$pendName = "P3E Pending Co $sfx"
$pc = Api POST '/api/company' -Token $PendTok -Body (Json @{ name = $pendName; creditCode = (New-CreditCode); city = $CITY_HZ })
$PendCompany = [int]$pc.Json.data
Assert-Step 'setup: unverified company created' ($pc.Json.code -eq 0 -and $PendCompany -gt 0) ("companyId={0}" -f $PendCompany)

$svP = Api POST "/api/company/$PendCompany/verify" -Token $PendTok -Body (Json @{ legalPersonName = 'Tester E' })
$PendToken = [string]$svP.Json.data.authorizationToken
Assert-Step 'setup: submit verify returns an authorization token (D-24)' ($svP.Json.code -eq 0 -and $PendToken.Length -ge 8) ("tokenLen={0}" -f $PendToken.Length)

# ------------------------------------------------------------------
# A) company search (company_index / D-22)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## A) GET /api/company/search (company_index) ##########'
$co = Api GET "/api/company/$CompanyId" -Token $BobTok
$coName = [string]$co.Json.data.name
$kw = ([regex]::Match($coName, '\d{4,}')).Value
if (-not $kw) { $kw = ($coName -split '\s+')[0] }
Write-Host ("  approved company name=[{0}] search keyword=[{1}]" -f $coName, $kw)

Start-Sleep -Seconds 4   # company_index is filled asynchronously over MQ
$cs = Api GET "/api/company/search?keyword=$kw&size=20" -Token $AliceTok
Assert-Step 'A: company search responds' ($cs.Json.code -eq 0) ("total={0}" -f $cs.Json.data.total)
$hitCo = @($cs.Json.data.companies) | Where-Object { [int]$_.id -eq $CompanyId }
Assert-Step 'A: approved company is searchable' ([bool]$hitCo) ("total={0}" -f $cs.Json.data.total)

$pendKw = ([regex]::Match($pendName, '\d{4,}')).Value
$cs2 = Api GET "/api/company/search?keyword=$pendKw&size=20" -Token $AliceTok
$hitPend = @($cs2.Json.data.companies) | Where-Object { [int]$_.id -eq $PendCompany }
Assert-Step 'A: UNVERIFIED company is NOT searchable (only verified are indexed)' (-not $hitPend) ("total={0}" -f $cs2.Json.data.total)

# ------------------------------------------------------------------
# B) invite code -> join (part of the phase-1 checklist)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## B) POST /api/company/{id}/invite-code + /join ##########'
$ic = Api POST "/api/company/$CompanyId/invite-code" -Token $BobTok
$invite = [string]$ic.Json.data
Assert-Step 'B: OWNER generates an invite code' ($ic.Json.code -eq 0 -and $invite.Length -ge 4) ("code={0}" -f $invite)

$JoinU = "p3ej$sfx"
$joinUser = New-User $JoinU ('139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString())
$JoinTok = $joinUser.Token
$JoinUserId = $joinUser.UserId
$jn = Api POST '/api/company/join' -Token $JoinTok -Body (Json @{ inviteCode = $invite })
Assert-Step 'B: a new user joins with the code' ($jn.Json.code -eq 0) $jn.Text

$mem = Api GET "/api/company/$CompanyId/members" -Token $BobTok
$joined = @($mem.Json.data) | Where-Object { [int]$_.userId -eq [int]$JoinUserId }
Assert-Step 'B: joined user appears in company members' ([bool]$joined) ("members={0} joinedUserId={1}" -f @($mem.Json.data).Count, $JoinUserId)

$jnBad = Api POST '/api/company/join' -Token $AliceTok -Body (Json @{ inviteCode = 'NO-SUCH-CODE' })
Assert-Step 'B: bogus invite code is rejected' ($jnBad.Json.code -ne 0) ("http={0} code={1}" -f $jnBad.Http, $jnBad.Json.code)

# ------------------------------------------------------------------
# C) legal-person authorization (D-24 third layer; gateway-whitelisted, no JWT)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## C) POST /api/company/authorize ##########'
$az = Api POST '/api/company/authorize' -Body (Json @{ token = $PendToken; realName = 'Tester E'; idCard = $ID_CARD_OK })
Assert-Step 'C: valid authorization token accepted (no JWT needed)' ($az.Json.code -eq 0) $az.Text

$azBad = Api POST '/api/company/authorize' -Body (Json @{ token = 'not-a-real-token'; realName = 'Tester E'; idCard = $ID_CARD_OK })
Assert-Step 'C: bogus authorization token rejected' ($azBad.Json.code -ne 0) ("http={0} code={1}" -f $azBad.Http, $azBad.Json.code)

# ------------------------------------------------------------------
# D) HR self-offline (distinct from admin force-offline)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## D) POST /api/job/{id}/offline (HR) ##########'
function New-Job([string]$token, [int]$companyId, [string]$title) {
    $jd = Api POST '/api/job/draft' -Token $token -Body (Json @{ companyId = $companyId; title = $title; city = $CITY_HZ; salaryMin = 20000; salaryMax = 35000 })
    $jid = [int]$jd.Json.data
    $pub = Api POST "/api/job/$jid/publish" -Token $token
    return [pscustomobject]@{ JobId = $jid; Draft = $jd.Json.code; Publish = $pub.Json.code; Text = $pub.Text }
}

$infoD1 = New-Job $BobTok $CompanyId "P3E Offline Job $sfx"
$JobD1 = $infoD1.JobId
Assert-Step 'D: HR publishes a job to off-line' ($infoD1.Draft -eq 0 -and $infoD1.Publish -eq 0 -and $JobD1 -gt 0) $infoD1.Text

$offNoAuth = Api POST "/api/job/$JobD1/offline" -Token $AliceTok
Assert-Step 'D: non-member cannot off-line it (403/20003)' ($offNoAuth.Json.code -eq 20003 -and $offNoAuth.Http -eq 403) ("http={0} code={1}" -f $offNoAuth.Http, $offNoAuth.Json.code)

$offD1 = Api POST "/api/job/$JobD1/offline" -Token $BobTok
Assert-Step 'D: HR self-off-line succeeds' ($offD1.Json.code -eq 0) $offD1.Text
$jgD1 = Api GET "/api/job/$JobD1" -Token $BobTok
Assert-Step 'D: job status=2 with an offline reason' ($jgD1.Json.data.status -eq 2 -and $jgD1.Json.data.offlineReason) ("status={0} reason={1}" -f $jgD1.Json.data.status, $jgD1.Json.data.offlineReason)

# ------------------------------------------------------------------
# E) notification read / read-all
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## E) PUT /api/notification/{id}/read + /read-all ##########'
# Do NOT assume e2e left unread notifications for the seeker: in the e2e flow the
# delivery notifications go to the HR. Create one deterministically instead --
# an interview 31 minutes out means its "1 day before" reminder is already due,
# so the reminder fires immediately and produces a notification for the seeker
# (same technique phase3b uses).
$soon = (Get-Date).AddMinutes(31).ToString('yyyy-MM-ddTHH:mm:ss')
$ivE = Api POST '/api/interview' -Token $BobTok -Body (Json @{ deliveryId = $DeliveryId; interviewTime = $soon; interviewType = 'ONLINE'; addressOrLink = 'https://meet.example.com/p3e'; interviewerName = 'HR' })
Assert-Step 'E: HR creates an interview to generate a notification' ($ivE.Json.code -eq 0) $ivE.Text

$n0 = 0
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Seconds 3
    $n0 = [int](Api GET '/api/notification/unread-count' -Token $AliceTok).Json.data
    if ($n0 -ge 1) { break }
}
$uc0 = Api GET '/api/notification/unread-count' -Token $AliceTok
Assert-Step 'E: unread-count readable and non-zero' ($uc0.Json.code -eq 0 -and $n0 -ge 1) ("unread={0}" -f $n0)

$nlist = Api GET '/api/notification/list' -Token $AliceTok
$firstId = [int](@($nlist.Json.data)[0]).id
Assert-Step 'E: notification list has items' ($nlist.Json.code -eq 0 -and $firstId -gt 0) ("count={0} firstId={1}" -f @($nlist.Json.data).Count, $firstId)

$rd = Api PUT "/api/notification/$firstId/read" -Token $AliceTok
Assert-Step 'E: mark one as read' ($rd.Json.code -eq 0) $rd.Text
$uc1 = Api GET '/api/notification/unread-count' -Token $AliceTok
$n1 = [int]$uc1.Json.data
Assert-Step 'E: unread-count did not increase after read' ($n1 -le $n0) ("before={0} after={1}" -f $n0, $n1)

$ra = Api PUT '/api/notification/read-all' -Token $AliceTok
Assert-Step 'E: read-all succeeds' ($ra.Json.code -eq 0) $ra.Text
$uc2 = Api GET '/api/notification/unread-count' -Token $AliceTok
Assert-Step 'E: unread-count is 0 after read-all' ([int]$uc2.Json.data -eq 0) ("unread={0}" -f $uc2.Json.data)

# ------------------------------------------------------------------
# F) refresh resume index manually
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## F) POST /api/resume/{id}/refresh-index ##########'
# Only the owner path is asserted: the endpoint lives in the HR controller and a
# verified HR may legitimately be allowed to refresh too, so inventing a negative
# here would be guessing at the rule rather than testing it.
$ri = Api POST "/api/resume/$ResumeId/refresh-index" -Token $AliceTok
Assert-Step 'F: owner can refresh the resume index' ($ri.Json.code -eq 0) $ri.Text
$rAfter = Api GET "/api/resume/$ResumeId" -Token $AliceTok
Assert-Step 'F: resume still readable afterwards' ($rAfter.Json.code -eq 0) ("parseStatus={0}" -f $rAfter.Json.data.parseStatus)

# ------------------------------------------------------------------
# G) Swagger UI (service port directly: the gateway only routes /api/**)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## G) GET /swagger-ui.html (notification service :9007) ##########'
$sw = ApiAbs 'GET' "$NOTIFY_BASE/swagger-ui.html" '' ''
Assert-Step 'G: /swagger-ui.html redirects (302)' ($sw.Http -eq 302) ("http={0}" -f $sw.Http)
$docs = ApiAbs 'GET' "$NOTIFY_BASE/v3/api-docs" '' ''
Assert-Step 'G: /v3/api-docs serves an OpenAPI document' ($docs.Http -eq 200 -and $docs.Text -match '"openapi"') ("http={0} len={1}" -f $docs.Http, $docs.Text.Length)

# ------------------------------------------------------------------
# H) natural-language search (D-29)
# ------------------------------------------------------------------
Write-Host ''
Write-Host '########## H) GET /api/job/search/nl (D-29) ##########'
$envDump = (& docker inspect hirehub-job --format '{{range .Config.Env}}{{println .}}{{end}}' 2>&1) -join "`n"
$hasKey = ($envDump -match 'DASHSCOPE_API_KEY=.+')
Write-Host ("  DASHSCOPE_API_KEY present in container: {0}" -f $hasKey)

$logBefore = (& docker logs hirehub-job 2>&1 | Select-String -Pattern 'NL' | Measure-Object).Count
$nlq = -join ([char]0x676D, [char]0x5DDE) + ' 30k ' + [char]0x4EE5 + [char]0x4E0A + ' Java'
$nl = Api GET ("/api/job/search/nl?q=" + [uri]::EscapeDataString($nlq) + "&size=10") -Token $AliceTok
Assert-Step 'H: /search/nl responds 200' ($nl.Json.code -eq 0) ("total={0}" -f $nl.Json.data.total)

Start-Sleep -Seconds 2
$newLogs = (& docker logs hirehub-job 2>&1 | Select-String -Pattern 'NL' | Select-Object -Skip $logBefore) -join "`n"
$llmOk = $newLogs -match 'LLM'
$degraded = $newLogs -match ([char]0x964D + [char]0x7EA7) -or $newLogs -match 'unconfigured|not configured'

if ($hasKey) {
    Assert-Step 'H: LLM branch actually ran (success log, no degrade warning)' ($llmOk -and -not $degraded) ("newLogs={0}" -f ($newLogs -replace "`r?`n", ' | '))
} else {
    Assert-Step 'H: DEGRADED branch works (no key -> falls back to keyword)' ($nl.Json.code -eq 0) 'see NOTICE below'
    Write-Host ''
    Write-Host '  ***************************************************************'
    Write-Host '  NOTICE: the LLM branch was NOT verified (no DASHSCOPE_API_KEY).'
    Write-Host '  Add DASHSCOPE_API_KEY to docker/.env and re-run this script to'
    Write-Host '  verify the real qwen call. Do NOT record D-29 as verified yet.'
    Write-Host '  ***************************************************************'
}

Write-Host ''
Show-Summary
