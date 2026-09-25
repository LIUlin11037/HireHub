#requires -Version 5.1
<#
  HireHub phase-3 verification: D-02 (dual token), D-06 (/me/identities),
  D-07 (@PreAuthorize + admin endpoints + audit), D-23 (seeker privacy).

  Usage:
    .\scripts\phase3-verify.ps1

  NOTE: this file is intentionally pure ASCII. Chinese literals are built at
  runtime with [char]0xXXXX so the script has no encoding/BOM dependency
  (see docs/.md #9: .ps1 must be UTF-8 with BOM for PS 5.1, else it breaks).
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_ = '123456'
$ID_CARD_OK = '110101199003078515'
$ADMIN_U = 'admin'
$ADMIN_P = 'admin123'

# Chinese literals, built at runtime to keep this file ASCII-only
$NAME_LI    = -join ([char]0x674E, [char]0x5C0F, [char]0x660E)                                  # real name
$NAME_WANG  = -join ([char]0x738B, [char]0x6D4B, [char]0x8BD5)                                  # resume name
$CITY_HZ    = -join ([char]0x676D, [char]0x5DDE)                                                # city
$JOB_OPEN   = -join ([char]0x5728, [char]0x804C, '-', [char]0x8003, [char]0x8651, [char]0x673A, [char]0x4F1A)  # job status: open to offers

# ---------- fresh identities + valid GB32100 credit code ----------
$sfx = Get-Random -Minimum 1000 -Maximum 9999
$SeekerU = "p3s$sfx"
$HrU     = "p3h$sfx"
$PhoneS  = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
$PhoneH  = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
$ResumePhone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()

$ALPHABET = '0123456789ABCDEFGHJKLMNPQRTUWXY'
$W = @(1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)
$base17 = ('91330100' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()).Substring(0, 17)
$sum = 0
for ($i = 0; $i -lt 17; $i++) { $sum += $ALPHABET.IndexOf($base17[$i]) * $W[$i] }
$CreditCode = $base17 + $ALPHABET[(31 - $sum % 31) % 31]

Write-Host ("seeker={0} hr={1} creditCode={2}" -f $SeekerU, $HrU, $CreditCode)

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function Login([string]$u, [string]$p) {
    return (Api POST '/api/auth/login' -Body (Json @{ username = $u; password = $p })).Json.data
}

Write-Host ''
Write-Host '########## Setup: seeker + HR + verified company ##########'

Api POST '/api/auth/register' -Body (Json @{ username = $SeekerU; password = $PWD_; phone = $PhoneS }) | Out-Null
$s = Login $SeekerU $PWD_
$SeekerTok = $s.accessToken
Assert-Step 'setup: seeker login' ([bool]$SeekerTok) ("userId={0}" -f $s.userId)

$rn = Api POST '/api/auth/real-name' -Token $SeekerTok -Body (Json @{ realName = $NAME_LI; idCard = $ID_CARD_OK })
Assert-Step 'setup: seeker real-name' ($rn.Json.code -eq 0) $rn.Text

Api POST '/api/auth/register' -Body (Json @{ username = $HrU; password = $PWD_; phone = $PhoneH }) | Out-Null
$h = Login $HrU $PWD_
$HrTok = $h.accessToken
Assert-Step 'setup: HR login' ([bool]$HrTok) ("userId={0}" -f $h.userId)
$rn2 = Api POST '/api/auth/real-name' -Token $HrTok -Body (Json @{ realName = $NAME_LI; idCard = $ID_CARD_OK })
Assert-Step 'setup: HR real-name' ($rn2.Json.code -eq 0) $rn2.Text

$cc = Api POST '/api/company' -Token $HrTok -Body (Json @{ name = "P3 Tech $sfx"; creditCode = $CreditCode; city = $CITY_HZ })
$CompanyId = [int]$cc.Json.data
Assert-Step 'setup: create company' ($cc.Json.code -eq 0 -and $CompanyId -gt 0) ("companyId={0}" -f $CompanyId)

# No license -> MEDIUM risk -> stays PENDING, so an admin approval is needed.
# That also gives us an admin operation to audit (D-07).
$sv = Api POST "/api/company/$CompanyId/verify" -Token $HrTok -Body (Json @{ legalPersonName = $NAME_LI })
Assert-Step 'setup: submit verify (risk MEDIUM, stays PENDING)' ($sv.Json.data.riskLevel -eq 'MEDIUM') $sv.Text

Write-Host ''
Write-Host '########## D-06 / D-07: admin verifies the company ##########'
$adm = Login $ADMIN_U $ADMIN_P
$AdmTok = $adm.accessToken
Assert-Step 'admin login' ([bool]$AdmTok) ("roles={0}" -f ($adm.roles -join ','))

$lst = Api GET '/api/company/admin/list' -Token $AdmTok
$pendingHit = $false
foreach ($c in @($lst.Json.data)) { if ([int]$c.id -eq $CompanyId) { $pendingHit = $true } }
Assert-Step 'admin pending list contains the company' $pendingHit ("count={0}" -f @($lst.Json.data).Count)

$av = Api POST "/api/company/admin/$CompanyId/verify" -Token $AdmTok -Body (Json @{ approve = $true; remark = 'p3' })
Assert-Step 'admin approve (via @PreAuthorize)' ($av.Json.code -eq 0) $av.Text
$vs = Api GET "/api/company/$CompanyId" -Token $HrTok
Assert-Step 'company verifyStatus=1' ($vs.Json.data.verifyStatus -eq 1) $vs.Text

Write-Host ''
Write-Host '########## D-07: job admin endpoints + @PreAuthorize ##########'
$jd = Api POST '/api/job/draft' -Token $HrTok -Body (Json @{ companyId = $CompanyId; title = 'P3 Java Engineer'; city = $CITY_HZ; salaryMin = 20000; salaryMax = 35000 })
$JobId = [int]$jd.Json.data
Assert-Step 'HR creates job draft' ($jd.Json.code -eq 0 -and $JobId -gt 0) ("jobId={0}" -f $JobId)
$pub = Api POST "/api/job/$JobId/publish" -Token $HrTok
Assert-Step 'HR publishes job' ($pub.Json.code -eq 0) $pub.Text

$stat403 = Api GET '/api/job/admin/statistics' -Token $HrTok
Assert-Step 'non-admin blocked from /job/admin/statistics (403/20003)' ($stat403.Json.code -eq 20003 -and $stat403.Http -eq 403) ("http={0} code={1}" -f $stat403.Http, $stat403.Json.code)

$stat = Api GET '/api/job/admin/statistics' -Token $AdmTok
Assert-Step 'admin gets /job/admin/statistics' ($stat.Json.code -eq 0) ("total={0} online={1} cities={2}" -f $stat.Json.data.total, $stat.Json.data.online, @($stat.Json.data.topCities).Count)

$off403 = Api PUT "/api/job/admin/$JobId/offline?reason=p3test" -Token $HrTok
Assert-Step 'non-admin blocked from /job/admin/{id}/offline (403/20003)' ($off403.Json.code -eq 20003) ("http={0} code={1}" -f $off403.Http, $off403.Json.code)

$off = Api PUT "/api/job/admin/$JobId/offline?reason=p3test" -Token $AdmTok
Assert-Step 'admin force-offlines the job' ($off.Json.code -eq 0) $off.Text
$jg = Api GET "/api/job/$JobId" -Token $HrTok
Assert-Step 'job status=2 (offline)' ($jg.Json.data.status -eq 2) ("status={0}" -f $jg.Json.data.status)

$log = Api GET '/api/auth/admin/operation-log?limit=50' -Token $AdmTok
$logTxt = $log.Text
Assert-Step 'audit: operation-log readable by admin' ($log.Json.code -eq 0) ("count={0}" -f @($log.Json.data).Count)
Assert-Step 'audit: contains VERIFY_APPROVE' ($logTxt -match 'VERIFY_APPROVE') ''
Assert-Step 'audit: contains JOB_OFFLINE' ($logTxt -match 'JOB_OFFLINE') ''
Assert-Step 'audit: traceId recorded (JOB_OFFLINE row has non-null traceId)' ($logTxt -match '"traceId":"[0-9a-f]{16,}"') ''
$log403 = Api GET '/api/auth/admin/operation-log' -Token $HrTok
Assert-Step 'non-admin blocked from /auth/admin/operation-log (403/20003)' ($log403.Json.code -eq 20003) ("http={0} code={1}" -f $log403.Http, $log403.Json.code)

Write-Host ''
Write-Host '########## D-02: dual token / rotation / logout blacklist ##########'
$l = Api POST '/api/auth/login' -Body (Json @{ username = $SeekerU; password = $PWD_ })
$A1 = $l.Json.data.accessToken
$R1 = $l.Json.data.refreshToken
Assert-Step 'D-02 login returns refreshToken' ([bool]$R1) ("expiresIn={0}" -f $l.Json.data.expiresIn)
Assert-Step 'D-02 login returns expiresIn' ($l.Json.data.expiresIn -gt 0) ("expiresIn={0}" -f $l.Json.data.expiresIn)

$rf = Api POST '/api/auth/refresh' -Body (Json @{ refreshToken = $R1 })
$A2 = $rf.Json.data.accessToken
$R2 = $rf.Json.data.refreshToken
Assert-Step 'D-02 refresh rotates tokens' ($rf.Json.code -eq 0 -and $A2 -and $R2 -and $R2 -ne $R1) ("newAccess={0} rotated={1}" -f [bool]$A2, ($R2 -ne $R1))

$rf2 = Api POST '/api/auth/refresh' -Body (Json @{ refreshToken = $R1 })
Assert-Step 'D-02 reused refresh token rejected (rotation)' ($rf2.Json.code -ne 0) ("http={0} code={1} msg={2}" -f $rf2.Http, $rf2.Json.code, $rf2.Json.message)

$me2 = Api GET '/api/auth/me' -Token $A2
Assert-Step 'D-02 rotated access token works' ($me2.Json.code -eq 0) $me2.Text

$lo = Api POST '/api/auth/logout' -Token $A2 -Body (Json @{ refreshToken = $R2 })
Assert-Step 'D-02 logout' ($lo.Json.code -eq 0) $lo.Text
$after = Api GET '/api/auth/me' -Token $A2
Assert-Step 'D-02 blacklisted access token rejected (401)' ($after.Http -eq 401) ("http={0} code={1}" -f $after.Http, $after.Json.code)
$rf3 = Api POST '/api/auth/refresh' -Body (Json @{ refreshToken = $R2 })
Assert-Step 'D-02 refresh after logout rejected' ($rf3.Json.code -ne 0) ("http={0} code={1}" -f $rf3.Http, $rf3.Json.code)

Write-Host ''
Write-Host '########## D-06: /api/auth/me/identities ##########'
$idn = Api GET '/api/auth/me/identities' -Token $HrTok
Assert-Step 'D-06 identities readable' ($idn.Json.code -eq 0) $idn.Text
Assert-Step 'D-06 globalRoles present' (@($idn.Json.data.globalRoles) -contains 'SEEKER') ("globalRoles={0}" -f (@($idn.Json.data.globalRoles) -join ','))
$myCo = $idn.Json.data.companies | Where-Object { [int]$_.companyId -eq $CompanyId }
Assert-Step 'D-06 company identity present with OWNER role' ($myCo -and $myCo.role -eq 'OWNER') ("companies={0}" -f @($idn.Json.data.companies).Count)
Assert-Step 'D-06 verifyStatus rendered as label' ($myCo.verifyStatus -match '^[\u4e00-\u9fa5]+$') ("verifyStatus={0} code={1}" -f $myCo.verifyStatus, $myCo.verifyStatusCode)

Write-Host ''
Write-Host '########## D-23: seeker privacy (anonymize / block / consent) ##########'
$cr = Api POST '/api/resume' -Token $SeekerTok -Body (Json @{ title = 'P3 resume'; name = $NAME_WANG; phone = $ResumePhone; expectCity = $CITY_HZ; expectPosition = 'Java'; status = 1 })
$ResumeId = [int]$cr.Json.data
Assert-Step 'D-23 seeker creates public resume' ($cr.Json.code -eq 0 -and $ResumeId -gt 0) ("resumeId={0}" -f $ResumeId)

$pf = Api PUT '/api/resume/preference' -Token $SeekerTok -Body (Json @{ jobStatus = $JOB_OPEN; expectCity = $CITY_HZ })
Assert-Step 'D-23 seeker sets job status (searchable)' ($pf.Json.code -eq 0) $pf.Text
Start-Sleep -Seconds 2   # ES near-real-time refresh

$sr = Api GET '/api/resume/search?keyword=Java&size=50' -Token $HrTok -CompanyId $CompanyId
Assert-Step 'D-23 verified-company HR can search talent pool' ($sr.Json.code -eq 0) ("total={0}" -f $sr.Json.data.total)
$hit = @($sr.Json.data.items) | Where-Object { [int]$_.resume.id -eq $ResumeId }
Assert-Step 'D-23 seeker resume is searchable' ([bool]$hit) ''
Assert-Step 'D-23 search result is anonymized (no real name)' ($sr.Text -notmatch [regex]::Escape($NAME_WANG)) ''
Assert-Step 'D-23 search result leaks no phone' ($sr.Text -notmatch $ResumePhone) ''
Assert-Step 'D-23 search result carries anonymousName' ($sr.Text -match 'anonymousName') ''

$det = Api GET "/api/resume/$ResumeId/hr-detail" -Token $HrTok -CompanyId $CompanyId
Assert-Step 'D-23 HR can open detail while consent absent' ($det.Json.code -eq 0) $det.Text
Assert-Step 'D-23 detail: contactRevealed=false' ($det.Json.data.contactRevealed -eq $false) ''
Assert-Step 'D-23 detail without consent: no name/phone/email in body' (($det.Text -notmatch [regex]::Escape($NAME_WANG)) -and ($det.Text -notmatch $ResumePhone)) ''

$rq = Api POST "/api/resume/$ResumeId/contact-request" -Token $HrTok -CompanyId $CompanyId
$ConsentId = [int]$rq.Json.data
Assert-Step 'D-23 HR requests contact' ($rq.Json.code -eq 0 -and $ConsentId -gt 0) ("consentId={0}" -f $ConsentId)

$cons = Api GET '/api/resume/contact-consents' -Token $SeekerTok
Assert-Step 'D-23 seeker sees pending consent' ($cons.Text -match [string]$ResumeId) ("count={0}" -f @($cons.Json.data).Count)

$agree = Api POST "/api/resume/consent/$ConsentId`?agree=true" -Token $SeekerTok
Assert-Step 'D-23 seeker agrees' ($agree.Json.code -eq 0) $agree.Text

$det2 = Api GET "/api/resume/$ResumeId/hr-detail" -Token $HrTok -CompanyId $CompanyId
Assert-Step 'D-23 after consent: contactRevealed=true' ($det2.Json.data.contactRevealed -eq $true) ''
Assert-Step 'D-23 after consent: phone visible' ($det2.Text -match $ResumePhone) ''

# block the HR's company -> block must win over the earlier consent
$blk = Api PUT '/api/resume/preference' -Token $SeekerTok -Body (Json @{ jobStatus = $JOB_OPEN; expectCity = $CITY_HZ; blockedCompanyIds = "[$CompanyId]" })
Assert-Step 'D-23 seeker blocks the company' ($blk.Json.code -eq 0) $blk.Text
Start-Sleep -Seconds 2

$det3 = Api GET "/api/resume/$ResumeId/hr-detail" -Token $HrTok -CompanyId $CompanyId
Assert-Step 'D-23 blocked company gets 404 even with consent' ($det3.Http -eq 404) ("http={0} code={1}" -f $det3.Http, $det3.Json.code)

$sr2 = Api GET '/api/resume/search?keyword=Java&size=50' -Token $HrTok -CompanyId $CompanyId
$hit2 = @($sr2.Json.data.items) | Where-Object { [int]$_.resume.id -eq $ResumeId }
Assert-Step 'D-23 blocked resume gone from search' (-not $hit2) ("total={0}" -f $sr2.Json.data.total)

$lst2 = Api GET '/api/resume/preference' -Token $SeekerTok
Assert-Step 'D-23 preference round-trip keeps blockedCompanyIds' ($lst2.Text -match "$CompanyId") $lst2.Text

$vl = Api GET "/api/resume/$ResumeId/view-logs" -Token $SeekerTok
Assert-Step 'D-23 view log records HR lookups' ($vl.Json.code -eq 0 -and @($vl.Json.data).Count -gt 0) ("count={0}" -f @($vl.Json.data).Count)

Write-Host ''
Show-Summary
