#requires -Version 5.1
<#
  HireHub phase-3 / M5.3 verification: Q-08 company verification periodic recheck
  + job takedown linkage (MQ main path) + fallback reconcile.

  Usage:
    .\scripts\phase3c-verify.ps1

  Requires: Docker stack up, gateway on http://localhost:9000, and the MySQL
  client reachable through `docker exec hirehub-mysql`.

  Fixture note (why the DB is touched):
    MockCompanyVerifier reports businessStatus="revoked" when the company NAME
    ends with -REVOKED. Approval runs through the same verifier, so we cannot
    build the fixture by naming the company that way up front -- it would be
    rejected at submit time. There is also no company-rename API. So we create +
    approve + publish normally, then flip the name directly in MySQL. That
    reproduces the real-world change ("alive when verified, deregistered later")
    without bypassing any logic under test -- the fixture changes the *external
    fact*, not the code path being verified.

  NOTE: intentionally pure ASCII; Chinese literals are built at runtime with
  [char]0xXXXX (see docs/pitfalls #9: .ps1 must be ASCII or UTF-8-with-BOM).
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_ = '123456ab'
$ID_CARD_OK = '110101199003078515'
$ADMIN_U = 'admin'
$ADMIN_P = 'admin123'

# Chinese literals, built at runtime to keep this file ASCII-only
$CITY_HZ     = -join ([char]0x676D, [char]0x5DDE)                                  # Hangzhou
$NAME_LI     = -join ([char]0x674E, [char]0x5C0F, [char]0x660E)                    # real name
$TXT_REVOKED = -join ([char]0x6CE8, [char]0x9500)                                  # "revoked"

$DB_COMPANY = 'hirehub_company'
$DB_JOB     = 'hirehub_job'

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function Login([string]$u, [string]$p) {
    return (Api POST '/api/auth/login' -Body (Json @{ username = $u; password = $p })).Json.data
}

# Runs a statement inside the MySQL container. The password warning goes to
# stderr, so it is filtered out instead of polluting the returned value.
function Sql([string]$db, [string]$q) {
    $out = & docker exec hirehub-mysql mysql -uroot -proot -N -B -D $db -e $q 2>&1
    $clean = $out | Where-Object { "$_" -notmatch 'password on the command line' }
    return ($clean -join "`n").Trim()
}

# GB32100 check digit -- same algorithm the service validates against
function New-CreditCode {
    $ALPHABET = '0123456789ABCDEFGHJKLMNPQRTUWXY'
    $W = @(1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)
    $base17 = ('91330100' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()).Substring(0, 17)
    $sum = 0
    for ($i = 0; $i -lt 17; $i++) { $sum += $ALPHABET.IndexOf($base17[$i]) * $W[$i] }
    return $base17 + $ALPHABET[(31 - $sum % 31) % 31]
}

$sfx = Get-Random -Minimum 1000 -Maximum 9999
$HrA = "p3ca$sfx"
$HrB = "p3cb$sfx"
$PhoneA = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
$PhoneB = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
Write-Host ("sfx={0} hrA={1} hrB={2}" -f $sfx, $HrA, $HrB)

# ---------- helpers: build a verified company owned by a fresh HR ----------
function New-VerifiedHr([string]$user, [string]$phone) {
    Api POST '/api/auth/register' -Body (Json @{ username = $user; password = $PWD_; phone = $phone }) | Out-Null
    $h = Login $user $PWD_
    Api POST '/api/auth/real-name' -Token $h.accessToken -Body (Json @{ realName = $NAME_LI; idCard = $ID_CARD_OK }) | Out-Null
    return $h.accessToken
}

function New-PublishedJob([string]$token, [int]$companyId, [string]$title) {
    $jd = Api POST '/api/job/draft' -Token $token -Body (Json @{ companyId = $companyId; title = $title; city = $CITY_HZ; salaryMin = 20000; salaryMax = 35000 })
    $jid = [int]$jd.Json.data
    $pub = Api POST "/api/job/$jid/publish" -Token $token
    return [pscustomobject]@{ JobId = $jid; DraftCode = $jd.Json.code; PublishCode = $pub.Json.code; PublishText = $pub.Text }
}

Write-Host ''
Write-Host '########## Setup: admin + two verified companies, each with a live job ##########'

$adm = Login $ADMIN_U $ADMIN_P
$AdmTok = $adm.accessToken
Assert-Step 'setup: admin login' ([bool]$AdmTok) ("roles={0}" -f ($adm.roles -join ','))

$TokA = New-VerifiedHr $HrA $PhoneA
Assert-Step 'setup: HR A real-named' ([bool]$TokA) ''

$ccA = Api POST '/api/company' -Token $TokA -Body (Json @{ name = "P3C Alpha $sfx"; creditCode = (New-CreditCode); city = $CITY_HZ })
$CompanyA = [int]$ccA.Json.data
Assert-Step 'setup: company A created' ($ccA.Json.code -eq 0 -and $CompanyA -gt 0) ("companyId={0}" -f $CompanyA)

$svA = Api POST "/api/company/$CompanyA/verify" -Token $TokA -Body (Json @{ legalPersonName = $NAME_LI })
Assert-Step 'setup: company A submitted for verification' ($svA.Json.code -eq 0) $svA.Text

$avA = Api POST "/api/company/admin/$CompanyA/verify" -Token $AdmTok -Body (Json @{ approve = $true; remark = 'p3c' })
Assert-Step 'setup: company A approved' ($avA.Json.code -eq 0) $avA.Text
$vsA = Api GET "/api/company/$CompanyA" -Token $TokA
Assert-Step 'setup: company A verifyStatus=1' ($vsA.Json.data.verifyStatus -eq 1) ("status={0}" -f $vsA.Json.data.verifyStatus)

# NOTE: PowerShell variable names are case-insensitive, so $infoA and $JobA must be
# genuinely different names -- writing "$JobA = $jobA.JobId" would overwrite the
# object we still need to assert on.
$infoA = New-PublishedJob $TokA $CompanyA "P3C Alpha Job $sfx"
$JobA = $infoA.JobId
$flagsA = "draft={0} publish={1} jobId={2}" -f $infoA.DraftCode, $infoA.PublishCode, $JobA
Assert-Step 'setup: job A published' ($infoA.DraftCode -eq 0 -and $infoA.PublishCode -eq 0 -and $JobA -gt 0) $flagsA

$TokB = New-VerifiedHr $HrB $PhoneB
$ccB = Api POST '/api/company' -Token $TokB -Body (Json @{ name = "P3C Beta $sfx"; creditCode = (New-CreditCode); city = $CITY_HZ })
$CompanyB = [int]$ccB.Json.data
Assert-Step 'setup: company B created' ($ccB.Json.code -eq 0 -and $CompanyB -gt 0) ("companyId={0}" -f $CompanyB)

$svB = Api POST "/api/company/$CompanyB/verify" -Token $TokB -Body (Json @{ legalPersonName = $NAME_LI })
Assert-Step 'setup: company B submitted for verification' ($svB.Json.code -eq 0) $svB.Text
$avB = Api POST "/api/company/admin/$CompanyB/verify" -Token $AdmTok -Body (Json @{ approve = $true; remark = 'p3c' })
Assert-Step 'setup: company B approved' ($avB.Json.code -eq 0) $avB.Text

$infoB = New-PublishedJob $TokB $CompanyB "P3C Beta Job $sfx"
$JobB = $infoB.JobId
$flagsB = "draft={0} publish={1} jobId={2}" -f $infoB.DraftCode, $infoB.PublishCode, $JobB
Assert-Step 'setup: job B published' ($infoB.DraftCode -eq 0 -and $infoB.PublishCode -eq 0 -and $JobB -gt 0) $flagsB

# Make both companies the OLDEST verified rows so they are guaranteed to fall
# inside recheckOnce()'s "verify_status=1 ORDER BY verify_time ASC LIMIT batchSize".
Sql $DB_COMPANY "UPDATE company SET verify_time='2000-01-01 00:00:00' WHERE id=$CompanyA" | Out-Null
Sql $DB_COMPANY "UPDATE company SET verify_time='2000-01-02 00:00:00' WHERE id=$CompanyB" | Out-Null

Start-Sleep -Seconds 4   # ES near-real-time refresh after publish
$s0 = Api GET "/api/job/search?keyword=$sfx&size=50" -Token $TokA
$hitA0 = @($s0.Json.data.jobs) | Where-Object { [int]$_.id -eq $JobA }
$hitB0 = @($s0.Json.data.jobs) | Where-Object { [int]$_.id -eq $JobB }
Assert-Step 'before: job A is searchable' ([bool]$hitA0) ("total={0}" -f $s0.Json.data.total)
Assert-Step 'before: job B is searchable' ([bool]$hitB0) ("total={0}" -f $s0.Json.data.total)

Write-Host ''
Write-Host '########## Q-08: periodic recheck revokes A, jobs go offline via MQ ##########'

# Simulate the real-world change: company A got deregistered after being verified
Sql $DB_COMPANY "UPDATE company SET name=CONCAT(name,'-REVOKED'), verify_time='2000-01-01 00:00:00' WHERE id=$CompanyA" | Out-Null

$rc = Api POST '/api/company/admin/recheck' -Token $AdmTok
$revoked = [int]$rc.Json.data
Assert-Step 'recheck: endpoint succeeds' ($rc.Json.code -eq 0) $rc.Text
Assert-Step 'recheck: at least one company revoked' ($revoked -ge 1) ("revoked={0}" -f $revoked)

$vsA2 = Api GET "/api/company/$CompanyA" -Token $TokA
Assert-Step 'recheck: company A verifyStatus=3 (revoked)' ($vsA2.Json.data.verifyStatus -eq 3) $vsA2.Text
Assert-Step 'recheck: company A verifyRemark says revoked' ("$($vsA2.Json.data.verifyRemark)" -match $TXT_REVOKED) ("remark={0}" -f $vsA2.Json.data.verifyRemark)

$jgA = Api GET "/api/job/$JobA" -Token $TokA
Assert-Step 'linkage: job A status=2 (offline)' ($jgA.Json.data.status -eq 2) $jgA.Text
$reasonA = Sql $DB_JOB "SELECT IFNULL(offline_reason,'') FROM job WHERE id=$JobA"
Assert-Step 'linkage: job A offline_reason recorded' (-not [string]::IsNullOrWhiteSpace($reasonA)) ("reason={0}" -f $reasonA)

Start-Sleep -Seconds 4
$s1 = Api GET "/api/job/search?keyword=$sfx&size=50" -Token $TokA
$hitA1 = @($s1.Json.data.jobs) | Where-Object { [int]$_.id -eq $JobA }
Assert-Step 'linkage: job A gone from search' (-not $hitA1) ("total={0}" -f $s1.Json.data.total)

$recA = Sql $DB_COMPANY "SELECT COUNT(*) FROM company_verify_record WHERE company_id=$CompanyA AND verify_channel='PERIODIC_RECHECK'"
Assert-Step 'recheck: verify record written (PERIODIC_RECHECK)' ([int]$recA -ge 1) ("rows={0}" -f $recA)

$cons = Sql $DB_JOB "SELECT COUNT(*) FROM mq_consume_log WHERE consumer='job-company-revoke'"
Assert-Step 'linkage: MQ main path consumed (consumer=job-company-revoke)' ([int]$cons -ge 1) ("rows={0}" -f $cons)

Write-Host ''
Write-Host '########## Q-08: the healthy company must be left alone (conservative) ##########'
$vsB2 = Api GET "/api/company/$CompanyB" -Token $TokB
Assert-Step 'control: company B verifyStatus stays 1' ($vsB2.Json.data.verifyStatus -eq 1) $vsB2.Text
$jgB = Api GET "/api/job/$JobB" -Token $TokB
Assert-Step 'control: job B stays online (status=1)' ($jgB.Json.data.status -eq 1) $jgB.Text

$rc2 = Api POST '/api/company/admin/recheck' -Token $AdmTok
Assert-Step 'recheck is idempotent (second run revokes 0)' ([int]$rc2.Json.data -eq 0) $rc2.Text

Write-Host ''
Write-Host '########## Q-08: fallback reconcile (pretend the MQ event was lost) ##########'
# Break company B straight in the DB: verification revoked but the job is still
# online and no event was ever published -- exactly what the reconcile is for.
Sql $DB_COMPANY "UPDATE company SET verify_status=3, verify_remark='fixture: event lost' WHERE id=$CompanyB" | Out-Null

$rcl = Api POST '/api/job/admin/reconcile-company-status' -Token $AdmTok
Assert-Step 'reconcile: endpoint succeeds' ($rcl.Json.code -eq 0) $rcl.Text
Assert-Step 'reconcile: at least one job offlined' ([int]$rcl.Json.data -ge 1) ("offlined={0}" -f $rcl.Json.data)

$jgB2 = Api GET "/api/job/$JobB" -Token $TokB
Assert-Step 'reconcile: job B now offline (status=2)' ($jgB2.Json.data.status -eq 2) $jgB2.Text

$rcl2 = Api POST '/api/job/admin/reconcile-company-status' -Token $AdmTok
Assert-Step 'reconcile is idempotent (second run offlines 0)' ([int]$rcl2.Json.data -eq 0) $rcl2.Text

Write-Host ''
Write-Host '########## Authorization + audit ##########'
$rc403 = Api POST '/api/company/admin/recheck' -Token $TokA
Assert-Step 'non-admin blocked from /company/admin/recheck (403/20003)' ($rc403.Json.code -eq 20003 -and $rc403.Http -eq 403) ("http={0} code={1}" -f $rc403.Http, $rc403.Json.code)

$rcl403 = Api POST '/api/job/admin/reconcile-company-status' -Token $TokA
Assert-Step 'non-admin blocked from /job/admin/reconcile-company-status (403/20003)' ($rcl403.Json.code -eq 20003 -and $rcl403.Http -eq 403) ("http={0} code={1}" -f $rcl403.Http, $rcl403.Json.code)

$log = Api GET '/api/auth/admin/operation-log?limit=50' -Token $AdmTok
$logTxt = $log.Text
Assert-Step 'audit: contains COMPANY_RECHECK' ($logTxt -match 'COMPANY_RECHECK') ''
Assert-Step 'audit: contains COMPANY_RECONCILE' ($logTxt -match 'COMPANY_RECONCILE') ''
Assert-Step 'audit: traceId recorded' ($logTxt -match '"traceId":"[0-9a-f]{16,}"') ''

Write-Host ''
Show-Summary
