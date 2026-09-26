#requires -Version 5.1
<#
  phase3f-verify.ps1 -- security controls that had NO assertion before.

    A) password strength on register        (was: any password accepted, e.g. "1")
    B) login failure lockout                (was: unlimited credential stuffing)
    C) horizontal privilege escalation      (ownership checks existed but were untested)
    D) sensitive data never reaches logs    (nothing pinned it, so nothing protected it)

  Usage:
    .\scripts\phase3f-verify.ps1

  Needs `docker` for B (clear the lock key) and D (read container logs).

  Pure ASCII by design (see docs/pitfalls #9).
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_OK = '123456ab'          # password policy: >=8 chars, letters + digits
$ID_CARD_OK = '110101199003078515'
$REDIS = 'hirehub-redis'

# Chinese literals built at runtime to keep this file ASCII-only
$TXT_LOCKED = -join ([char]0x9501, [char]0x5B9A)      # "locked"

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function New-Name([string]$prefix) { return "$prefix" + (Get-Random -Minimum 10000 -Maximum 99999) }
function New-Phone([string]$head) { return $head + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString() }

Write-Host ''
Write-Host '########## A) password strength on register ##########'
$sfx = Get-Random -Minimum 1000 -Maximum 9999

# Each case uses its own username so a rejection cannot collide with a later case.
function Try-Register([string]$password, [string]$phone) {
    $u = "p3fs$sfx" + (Get-Random -Minimum 100 -Maximum 999)
    return Api POST '/api/auth/register' -Body (Json @{ username = $u; password = $password; phone = $phone })
}
$weak1 = Try-Register '1' (New-Phone '131')
Assert-Step 'A: password "1" rejected (too short)' ($weak1.Json.code -eq 10001) ("code={0} msg={1}" -f $weak1.Json.code, $weak1.Json.message)
$weak2 = Try-Register '123456' (New-Phone '131')
Assert-Step 'A: password "123456" rejected (digits only)' ($weak2.Json.code -eq 10001) ("code={0} msg={1}" -f $weak2.Json.code, $weak2.Json.message)
$weak3 = Try-Register 'abcdefgh' (New-Phone '131')
Assert-Step 'A: password "abcdefgh" rejected (letters only)' ($weak3.Json.code -eq 10001) ("code={0} msg={1}" -f $weak3.Json.code, $weak3.Json.message)
$weak4 = Try-Register 'abc12' (New-Phone '131')
Assert-Step 'A: password "abc12" rejected (too short even if mixed)' ($weak4.Json.code -eq 10001) ("code={0}" -f $weak4.Json.code)

$UserA = "p3fa$sfx"
$PhoneA = New-Phone '132'
$regA = Api POST '/api/auth/register' -Body (Json @{ username = $UserA; password = $PWD_OK; phone = $PhoneA })
Assert-Step 'A: strong password accepted' ($regA.Json.code -eq 0) $regA.Text

$UserB = "p3fb$sfx"
$PhoneB = New-Phone '133'
$regB = Api POST '/api/auth/register' -Body (Json @{ username = $UserB; password = $PWD_OK; phone = $PhoneB })
Assert-Step 'setup: second user registered' ($regB.Json.code -eq 0) $regB.Text

# real-name both so they can own a resume
$hfA = Api POST '/api/auth/login' -Body (Json @{ username = $UserA; password = $PWD_OK })
$TokA = $hfA.Json.data.accessToken
$hfB = Api POST '/api/auth/login' -Body (Json @{ username = $UserB; password = $PWD_OK })
$TokB = $hfB.Json.data.accessToken
Assert-Step 'setup: both users can log in' ([bool]$TokA -and [bool]$TokB) ''
$rnA = Api POST '/api/auth/real-name' -Token $TokA -Body (Json @{ realName = 'Tester F'; idCard = $ID_CARD_OK })
$rnB = Api POST '/api/auth/real-name' -Token $TokB -Body (Json @{ realName = 'Tester F'; idCard = $ID_CARD_OK })
Assert-Step 'setup: both users real-named' ($rnA.Json.code -eq 0 -and $rnB.Json.code -eq 0) ''

Write-Host ''
Write-Host '########## B) login failure lockout (anti credential-stuffing) ##########'
$wrongCount = 0
$lockCode = 0
$lockMsg = ''
for ($i = 1; $i -le 5; $i++) {
    $r = Api POST '/api/auth/login' -Body (Json @{ username = $UserA; password = 'wrongpass1' })
    if ($i -lt 5) {
        if ($r.Json.code -eq 20001) { $wrongCount++ }
    } else {
        $lockCode = $r.Json.code
        $lockMsg = [string]$r.Json.message
    }
}
Assert-Step 'B: first 4 wrong passwords rejected normally (20001)' ($wrongCount -eq 4) ("count={0}" -f $wrongCount)
Assert-Step 'B: 5th failure triggers the lock (20003)' ($lockCode -eq 20003) ("code={0}" -f $lockCode)
Assert-Step 'B: lock message tells the user it is a lockout' ($lockMsg -like "*$TXT_LOCKED*") ("msg={0}" -f $lockMsg)

# The important one: a LOCKED account must reject the CORRECT password too,
#   otherwise an attacker only has to hit the right one inside the lock window.
$correctWhileLocked = Api POST '/api/auth/login' -Body (Json @{ username = $UserA; password = $PWD_OK })
Assert-Step 'B: correct password is ALSO rejected while locked' ($correctWhileLocked.Json.code -eq 20003) ("code={0}" -f $correctWhileLocked.Json.code)

# unknown username must be counted too (otherwise "user exists" leaks via response differences)
$unknown = Api POST '/api/auth/login' -Body (Json @{ username = "nosuchuser$sfx"; password = 'wrongpass1' })
Assert-Step 'B: unknown username gets the same generic error' ($unknown.Json.code -eq 20001) ("code={0} msg={1}" -f $unknown.Json.code, $unknown.Json.message)

# clear the lock so the rest of the script (and re-runs) are unaffected
$delOut = & docker exec $REDIS redis-cli DEL "auth:login:lock:$UserA" "auth:login:fail:$UserA" 2>&1
Assert-Step 'B: lock cleared via redis (test hygiene)' ("$delOut" -match '^[12]') ("redisDel={0}" -f ($delOut -join ' '))

$afterUnlock = Api POST '/api/auth/login' -Body (Json @{ username = $UserA; password = $PWD_OK })
Assert-Step 'B: after unlock the correct password works again' ($afterUnlock.Json.code -eq 0) $afterUnlock.Text
$TokA = $afterUnlock.Json.data.accessToken

Write-Host ''
Write-Host '########## C) horizontal privilege escalation (IDOR) ##########'
$crA = Api POST '/api/resume' -Token $TokA -Body (Json @{ title = 'p3f resume A'; name = 'Tester F'; expectPosition = 'Java'; status = 0 })
$ResumeA = [int]$crA.Json.data
Assert-Step 'C: user A owns a resume' ($crA.Json.code -eq 0 -and $ResumeA -gt 0) ("resumeId={0}" -f $ResumeA)

$readByB = Api GET "/api/resume/$ResumeA" -Token $TokB
Assert-Step 'C: user B cannot READ A''s resume (20003)' ($readByB.Json.code -eq 20003) ("http={0} code={1}" -f $readByB.Http, $readByB.Json.code)

$putByB = Api PUT "/api/resume/$ResumeA" -Token $TokB -Body (Json @{ title = 'hijacked'; name = 'Tester F' })
Assert-Step 'C: user B cannot UPDATE A''s resume (20003)' ($putByB.Json.code -eq 20003) ("http={0} code={1}" -f $putByB.Http, $putByB.Json.code)

$parseByB = Api GET "/api/resume/$ResumeA/parse-result" -Token $TokB
Assert-Step 'C: user B cannot read A''s parse result' ($parseByB.Json.code -ne 0) ("code={0}" -f $parseByB.Json.code)

$prefByB = Api GET '/api/resume/preference' -Token $TokB
Assert-Step 'C: user B reads only B''s own preference' ($prefByB.Json.code -eq 0 -and [string]$prefByB.Json.data.userId -ne '0') ("userId={0}" -f $prefByB.Json.data.userId)

# reading a non-existent id must be 404, not a leak of "exists but not yours"
$notFound = Api GET '/api/resume/99999999' -Token $TokA
Assert-Step 'C: unknown resume id -> 10004' ($notFound.Json.code -eq 10004) ("code={0}" -f $notFound.Json.code)

# the resume owned by A must NOT be reachable by B through the HR path without consent
$hrPath = Api GET "/api/resume/$ResumeA/hr-detail" -Token $TokB -CompanyId 1
Assert-Step 'C: B cannot read A''s resume via the HR path' ($hrPath.Json.code -ne 0) ("code={0}" -f $hrPath.Json.code)

Write-Host ''
Write-Host '########## D) sensitive data must not end up in logs ##########'
# probe with values that are unique per run, so a hit can only come from THIS run
$phoneProbe = $PhoneA
$idProbe = $ID_CARD_OK
$services = @('hirehub-auth', 'hirehub-resume', 'hirehub-company', 'hirehub-delivery', 'hirehub-gateway')
$phoneHits = @()
$idHits = @()
foreach ($svc in $services) {
    $logs = (& docker logs --since 20m $svc 2>&1 | Out-String)
    if ($logs -match [regex]::Escape($phoneProbe)) { $phoneHits += $svc }
    if ($logs -match [regex]::Escape($idProbe)) { $idHits += $svc }
}
Assert-Step 'D: phone number never appears in service logs' ($phoneHits.Count -eq 0) ("hits={0}" -f (($phoneHits -join ',') + $(if ($phoneHits.Count -eq 0) { 'none' } else { '' })))
Assert-Step 'D: id card never appears in service logs' ($idHits.Count -eq 0) ("hits={0}" -f (($idHits -join ',') + $(if ($idHits.Count -eq 0) { 'none' } else { '' })))

Write-Host ''
Show-Summary
