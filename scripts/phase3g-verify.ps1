#requires -Version 5.1
<#
  phase3g-verify.ps1 -- D-41: the three gaps the frontend reported.

    A) GET /api/job/mine           HR can list its own jobs -- including DRAFT and OFFLINE
                                   ones, which live nowhere in the ES index.
    B) GET /api/interview/company  HR can list the interviews it sent (was seeker-only).
    C) delivery lists carry jobTitle / companyName / seekerName, assembled with
                                   3 batch calls per page instead of N+1.
    D) the three batch /internal endpoints really return many names in ONE call.

  Setup is self-contained: it does NOT depend on logs/e2e-setup.log.

  Usage:
    .\scripts\phase3g-verify.ps1

  Needs `docker`? No. Pure HTTP through the gateway (plus direct service ports for D).

  Pure ASCII by design (docs/pitfalls #9); Chinese literals are built at runtime.
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_ = '123456ab'
$ID_CARD_OK = '110101199003078515'
$ADMIN_U = 'admin'
$ADMIN_P = 'admin123'

$NAME_LI   = -join ([char]0x674E, [char]0x5C0F, [char]0x660E)                      # Li Xiao Ming
$NAME_WANG = -join ([char]0x738B, [char]0x6D4B, [char]0x8BD5)                      # Wang Ce Shi
$CITY_HZ   = -join ([char]0x676D, [char]0x5DDE)                                    # Hangzhou

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function Login([string]$u, [string]$p) {
    return (Api POST '/api/auth/login' -Body (Json @{ username = $u; password = $p })).Json.data
}
function New-CreditCode {
    $alphabet = '0123456789ABCDEFGHJKLMNPQRTUWXY'
    $w = @(1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)
    $b17 = ('91330100' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()).Substring(0, 17)
    $sum = 0
    for ($i = 0; $i -lt 17; $i++) { $sum += $alphabet.IndexOf($b17[$i]) * $w[$i] }
    return $b17 + $alphabet[(31 - $sum % 31) % 31]
}
$sfx = Get-Random -Minimum 1000 -Maximum 9999

# =====================================================================
# Setup: seeker + two verified companies (A and B) + job + delivery + interview
# =====================================================================
Write-Host ''
Write-Host '########## Setup ##########'

$SeekU = "p3gs$sfx"; $HrAU = "p3ga$sfx"; $HrBU = "p3gb$sfx"
Api POST '/api/auth/register' -Body (Json @{ username = $SeekU; password = $PWD_; phone = ('135' + (Get-Random -Minimum 10000000 -Maximum 99999999)) }) | Out-Null
$seek = Login $SeekU $PWD_
$SeekTok = $seek.accessToken
Api POST '/api/auth/real-name' -Token $SeekTok -Body (Json @{ realName = $NAME_LI; idCard = $ID_CARD_OK }) | Out-Null
Assert-Step 'setup: seeker ready (real-named)' ([bool]$SeekTok) ("userId={0}" -f $seek.userId)

# --- admin, used to approve both companies ---
$AdmTok = (Login $ADMIN_U $ADMIN_P).accessToken
Assert-Step 'setup: admin login' ([bool]$AdmTok) ''

function New-VerifiedCompany([string]$hrUser, [string]$suffix) {
    Api POST '/api/auth/register' -Body (Json @{ username = $hrUser; password = $PWD_; phone = ('136' + (Get-Random -Minimum 10000000 -Maximum 99999999)) }) | Out-Null
    $h = Login $hrUser $PWD_
    Api POST '/api/auth/real-name' -Token $h.accessToken -Body (Json @{ realName = $NAME_LI; idCard = $ID_CARD_OK }) | Out-Null
    $cc = Api POST '/api/company' -Token $h.accessToken -Body (Json @{ name = "P3G Tech $suffix"; creditCode = (New-CreditCode); city = $CITY_HZ })
    $cid = [int]$cc.Json.data
    Api POST "/api/company/$cid/verify" -Token $h.accessToken -Body (Json @{ legalPersonName = $NAME_LI }) | Out-Null
    Api POST "/api/company/admin/$cid/verify" -Token $AdmTok -Body (Json @{ approve = $true; remark = 'p3g' }) | Out-Null
    return [pscustomobject]@{ Token = $h.accessToken; CompanyId = $cid; UserId = $h.userId }
}

$A = New-VerifiedCompany $HrAU 'Alpha'
$B = New-VerifiedCompany $HrBU 'Beta'
Assert-Step 'setup: company A created + approved' ($A.CompanyId -gt 0) ("companyId={0}" -f $A.CompanyId)
Assert-Step 'setup: company B created + approved' ($B.CompanyId -gt 0) ("companyId={0}" -f $B.CompanyId)

# --- resume (Chinese name, to prove the name survives the Feign round-trip) ---
$cr = Api POST '/api/resume' -Token $SeekTok -Body (Json @{ title = 'P3G resume'; name = $NAME_WANG; expectPosition = 'Java'; status = 1 })
$ResumeId = [int]$cr.Json.data
Assert-Step 'setup: seeker resume created' ($ResumeId -gt 0) ("resumeId={0}" -f $ResumeId)

# --- job: one published, one left as draft ---
$TitlePub   = "P3G-PUB-$sfx"
$TitleDraft = "P3G-DRAFT-$sfx"
$jd = Api POST '/api/job/draft' -Token $A.Token -Body (Json @{ companyId = $A.CompanyId; title = $TitlePub; city = $CITY_HZ; salaryMin = 20000; salaryMax = 35000 })
$JobPub = [int]$jd.Json.data
$pub = Api POST "/api/job/$JobPub/publish" -Token $A.Token
Assert-Step 'setup: published job is online' ($pub.Json.code -eq 0) ("jobId={0}" -f $JobPub)

$jd2 = Api POST '/api/job/draft' -Token $A.Token -Body (Json @{ companyId = $A.CompanyId; title = $TitleDraft; city = $CITY_HZ; salaryMin = 15000; salaryMax = 25000 })
$JobDraft = [int]$jd2.Json.data
Assert-Step 'setup: a SECOND job stays a draft' ($JobDraft -gt 0) ("draftJobId={0}" -f $JobDraft)

# --- delivery + interview ---
$ap = Api POST '/api/delivery' -Token $SeekTok -Body (Json @{ jobId = $JobPub; resumeId = $ResumeId })
$Deliv = [int]$ap.Json.data
Assert-Step 'setup: seeker applied' ($Deliv -gt 0) ("deliveryId={0}" -f $Deliv)

$when = (Get-Date).AddDays(3).ToString('yyyy-MM-ddTHH:mm:ss')
$iv = Api POST '/api/interview' -Token $A.Token -Body (Json @{ deliveryId = $Deliv; interviewTime = $when; interviewType = 'ONLINE'; addressOrLink = 'https://meet.example.com/p3g'; interviewerName = 'HR' })
$Ivid = [int]$iv.Json.data
Assert-Step 'setup: HR sent an interview' ($Ivid -gt 0) ("interviewId={0}" -f $Ivid)

# =====================================================================
# A) GET /api/job/mine  -- the whole point: drafts & offline jobs are invisible to /search
# =====================================================================
Write-Host ''
Write-Host '########## A) HR job list (draft + online + offline) ##########'

$srch = Api GET "/api/job/search?keyword=$TitleDraft" -Token $SeekTok
Assert-Step 'A: the DRAFT job is invisible to ES search (why /mine is needed)' ([int]$srch.Json.data.total -eq 0) ("searchTotal={0}" -f $srch.Json.data.total)

$mineAll = Api GET '/api/job/mine' -Token $A.Token -CompanyId $A.CompanyId
$ids = @($mineAll.Json.data.jobs | ForEach-Object { [int]$_.id })
Assert-Step 'A: /job/mine returns the company jobs' ($mineAll.Json.code -eq 0 -and $ids -contains $JobPub) ("total={0} ids={1}" -f $mineAll.Json.data.total, ($ids -join ','))
Assert-Step 'A: /job/mine CONTAINS the draft (invisible in ES)' ($ids -contains $JobDraft) ("draftJobId={0}" -f $JobDraft)

$mineDraft = Api GET '/api/job/mine?status=0' -Token $A.Token -CompanyId $A.CompanyId
$draftIds = @($mineDraft.Json.data.jobs | ForEach-Object { [int]$_.id })
Assert-Step 'A: ?status=0 returns exactly the drafts' (($draftIds -contains $JobDraft) -and -not ($draftIds -contains $JobPub)) ("draftIds={0}" -f ($draftIds -join ','))

# take the published one offline, then it must show up under status=2
$off = Api POST "/api/job/$JobPub/offline" -Token $A.Token
$mineOff = Api GET '/api/job/mine?status=2' -Token $A.Token -CompanyId $A.CompanyId
$offIds = @($mineOff.Json.data.jobs | ForEach-Object { [int]$_.id })
Assert-Step 'A: after offline it shows under ?status=2' (($off.Json.code -eq 0) -and ($offIds -contains $JobPub)) ("offlineIds={0}" -f ($offIds -join ','))

# paging must really page (this is what the MybatisPlus pagination interceptor is for)
$p1 = Api GET '/api/job/mine?page=1&size=1' -Token $A.Token -CompanyId $A.CompanyId
Assert-Step 'A: paging works (size=1 -> 1 row, total counts all)' (@($p1.Json.data.jobs).Count -eq 1 -and [int]$p1.Json.data.total -ge 2) ("rows={0} total={1}" -f @($p1.Json.data.jobs).Count, $p1.Json.data.total)

# someone who is NOT a member of company A must not read its job list
$notMember = Api GET '/api/job/mine' -Token $B.Token -CompanyId $A.CompanyId
Assert-Step 'A: non-member gets 403 (20003)' ($notMember.Json.code -eq 20003 -and $notMember.Http -eq 403) ("http={0} code={1}" -f $notMember.Http, $notMember.Json.code)

$noHeader = Api GET '/api/job/mine' -Token $A.Token
Assert-Step 'A: missing X-Company-Id is rejected' ($noHeader.Json.code -ne 0) ("http={0} code={1}" -f $noHeader.Http, $noHeader.Json.code)

# =====================================================================
# B) GET /api/interview/company
# =====================================================================
Write-Host ''
Write-Host '########## B) HR interview list ##########'

$ivc = Api GET '/api/interview/company' -Token $A.Token -CompanyId $A.CompanyId
$ivIds = @($ivc.Json.data | ForEach-Object { [int]$_.id })
Assert-Step 'B: HR sees the interview it sent' ($ivc.Json.code -eq 0 -and $ivIds -contains $Ivid) ("count={0} ids={1}" -f $ivIds.Count, ($ivIds -join ','))

$ivOwn = Api GET '/api/interview/mine' -Token $SeekTok
$ownIds = @($ivOwn.Json.data | ForEach-Object { [int]$_.id })
Assert-Step 'B: seeker still sees it via /mine (old path intact)' ($ownIds -contains $Ivid) ("count={0}" -f $ownIds.Count)

$ivOther = Api GET '/api/interview/company' -Token $B.Token -CompanyId $A.CompanyId
Assert-Step 'B: another company HR gets 403' ($ivOther.Json.code -eq 20003 -and $ivOther.Http -eq 403) ("http={0} code={1}" -f $ivOther.Http, $ivOther.Json.code)

$ivSeeker = Api GET '/api/interview/company' -Token $SeekTok -CompanyId $A.CompanyId
Assert-Step 'B: the seeker himself gets 403 (not an employee)' ($ivSeeker.Json.code -eq 20003) ("code={0}" -f $ivSeeker.Json.code)

# =====================================================================
# C) delivery lists carry the display names (no N+1)
# =====================================================================
Write-Host ''
Write-Host '########## C) delivery lists carry jobTitle / companyName / seekerName ##########'

$recv = Api GET '/api/delivery/received' -Token $A.Token -CompanyId $A.CompanyId
$row = @($recv.Json.data | Where-Object { [int]$_.id -eq $Deliv })
Assert-Step 'C: /received returns the delivery as a VO' ($row.Count -eq 1) ("delivered={0}" -f @($recv.Json.data).Count)
$r0 = $row[0]
Assert-Step 'C: jobTitle is filled and CORRECT' ($r0.jobTitle -eq $TitlePub) ("jobTitle={0}" -f $r0.jobTitle)
Assert-Step 'C: companyName is filled and CORRECT' ($r0.companyName -eq "P3G Tech Alpha") ("companyName={0}" -f $r0.companyName)
Assert-Step 'C: seekerName comes from the resume and survives UTF-8' ($r0.seekerName -eq $NAME_WANG) ("seekerName={0}" -f $r0.seekerName)

$mineD = Api GET '/api/delivery/mine' -Token $SeekTok
$m0 = @($mineD.Json.data | Where-Object { [int]$_.id -eq $Deliv })[0]
Assert-Step 'C: seeker-side /mine also carries jobTitle + companyName' ($m0.jobTitle -eq $TitlePub -and $m0.companyName -eq 'P3G Tech Alpha') ("jobTitle={0} companyName={1}" -f $m0.jobTitle, $m0.companyName)

$det = Api GET "/api/delivery/$Deliv" -Token $SeekTok
Assert-Step 'C: delivery detail carries jobTitle too' ($det.Json.data.jobTitle -eq $TitlePub) ("jobTitle={0}" -f $det.Json.data.jobTitle)

# =====================================================================
# D) the batch internal endpoints: many names in ONE call
# =====================================================================
Write-Host ''
Write-Host '########## D) batch /internal endpoints ##########'

$gw = $script:GW
$script:GW = 'http://localhost:9003'
$bt = Api GET "/internal/titles?ids=$JobPub,$JobDraft"
$script:GW = 'http://localhost:9000'
Assert-Step 'D: /internal/titles returns BOTH titles in one call' (@($bt.Json.data).Count -eq 2) ("count={0}" -f @($bt.Json.data).Count)

$script:GW = 'http://localhost:9004'
$bn = Api GET "/internal/names?ids=$ResumeId"
$script:GW = 'http://localhost:9000'
Assert-Step 'D: /internal/names (resume) returns the name' (@($bn.Json.data).Count -eq 1 -and $bn.Json.data[0].name -eq $NAME_WANG) ("name={0}" -f $bn.Json.data[0].name)

$script:GW = 'http://localhost:9002'
$bc = Api GET "/internal/names?ids=$($A.CompanyId),$($B.CompanyId)"
$script:GW = 'http://localhost:9000'
Assert-Step 'D: /internal/names (company) resolves both companies' (@($bc.Json.data).Count -eq 2) ("count={0}" -f @($bc.Json.data).Count)

# empty ids must be tolerated: the delivery service can legitimately call with an
# empty id set (e.g. every row in the page has a null id), and must not blow up.
$script:GW = 'http://localhost:9003'
$bEmpty = Api GET '/internal/titles?ids='
$script:GW = 'http://localhost:9000'
Assert-Step 'D: empty ?ids returns an empty list, not an error' ($bEmpty.Json.code -eq 0 -and @($bEmpty.Json.data).Count -eq 0) ("code={0} count={1}" -f $bEmpty.Json.code, @($bEmpty.Json.data).Count)

# and the gateway path must still work after all that host switching
$back = Api GET '/api/job/mine' -Token $A.Token -CompanyId $A.CompanyId
Assert-Step 'D: gateway path still works after the direct-port calls' ($back.Json.code -eq 0) ("code={0}" -f $back.Json.code)

Write-Host ''
Show-Summary
