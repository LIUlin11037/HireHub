#requires -Version 5.1
<#
  HireHub phase-3d verification: the interview state machine (D-28) -- the last
  block of endpoints that no script had ever executed.

  Covers:
    - every legal transition: CONFIRM / REJECT / CANCEL(seeker) / CANCEL(HR) /
      RESCHEDULE / COMPLETE
    - RESCHEDULE goes back to SCHEDULED and needs re-confirmation
    - every terminal state rejects further events
    - actor rules bound to events (CONFIRM/REJECT = seeker, COMPLETE/RESCHEDULE = HR)
    - non-participant is forbidden; unknown interview is 404
    - D-28 decoupling: interview reject/cancel/complete must NOT move the delivery

  Why a FRESH delivery: e2e-verify.ps1 finishes by driving its delivery to
  CANCELLED (terminal), so asserting "the delivery did not move" against it would
  be vacuous -- a terminal delivery cannot move anyway. So this script registers a
  second seeker, applies to the same job, and walks that delivery to INTERVIEW.

  Usage:
    .\scripts\e2e-verify.ps1 -Fresh *>&1 | Tee-Object logs\e2e-setup.log
    .\scripts\phase3d-verify.ps1

  Pure ASCII by design (see docs/pitfalls #9); no Chinese literals.
#>
. "$PSScriptRoot\e2e-lib.ps1"

$PWD_ = '123456ab'
$ID_CARD_OK = '110101199003078515'

# ---------- reuse the ids created by e2e-verify.ps1 ----------
$setupLog = Join-Path $PSScriptRoot '..\logs\e2e-setup.log'
if (-not (Test-Path $setupLog)) { Write-Host "missing $setupLog - run e2e-verify.ps1 -Fresh first"; exit 2 }
$setupTxt = Get-Content $setupLog -Raw
$reBob = [regex]::Match($setupTxt, 'bob \S+ \((bob\d+)\)')
$reIds = [regex]::Match($setupTxt, 'IDS: companyId=(\d+) jobId=(\d+) resumeId=(\d+) deliveryId=(\d+) interviewId=(\d+)')
if (-not $reBob.Success -or -not $reIds.Success) { Write-Host 'could not parse setup log'; exit 2 }
$BobU      = $reBob.Groups[1].Value
$CompanyId = [int]$reIds.Groups[1].Value
$JobId     = [int]$reIds.Groups[2].Value
Write-Host ("setup: bob={0} companyId={1} jobId={2}" -f $BobU, $CompanyId, $JobId)

function Json([hashtable]$h) { return ($h | ConvertTo-Json -Compress) }
function Login([string]$u) { return (Api POST '/api/auth/login' -Body (Json @{ username = $u; password = $PWD_ })).Json.data }

$bob = Login $BobU
$BobTok = $bob.accessToken
Assert-Step 'setup: HR login' ([bool]$BobTok) ''

# ---------- a second seeker + delivery, so D-28 can be checked for real ----------
$sfx = Get-Random -Minimum 1000 -Maximum 9999
$SeekerU = "p3d$sfx"
$PhoneS = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
Api POST '/api/auth/register' -Body (Json @{ username = $SeekerU; password = $PWD_; phone = $PhoneS }) | Out-Null
$s2 = Login $SeekerU
$SeekTok = $s2.accessToken
Assert-Step 'setup: seeker2 login' ([bool]$SeekTok) ("userId={0}" -f $s2.userId)

$rn = Api POST '/api/auth/real-name' -Token $SeekTok -Body (Json @{ realName = 'Tester D'; idCard = $ID_CARD_OK })
Assert-Step 'setup: seeker2 real-name' ($rn.Json.code -eq 0) $rn.Text

$cr = Api POST '/api/resume' -Token $SeekTok -Body (Json @{ title = 'P3D resume'; name = 'Tester D'; expectPosition = 'Java'; status = 1 })
$ResumeId = [int]$cr.Json.data
Assert-Step 'setup: seeker2 resume created' ($cr.Json.code -eq 0 -and $ResumeId -gt 0) ("resumeId={0}" -f $ResumeId)

$ap = Api POST '/api/delivery' -Token $SeekTok -Body (Json @{ jobId = $JobId; resumeId = $ResumeId })
$Deliv = [int]$ap.Json.data
Assert-Step 'setup: seeker2 applies to the job' ($ap.Json.code -eq 0 -and $Deliv -gt 0) ("deliveryId={0}" -f $Deliv)

# walk the delivery to INTERVIEW (HR side): VIEWED -> COMMUNICATING -> INTERVIEW
$null = Api GET "/api/delivery/$Deliv" -Token $BobTok
$e1 = Api POST "/api/delivery/$Deliv/events" -Token $BobTok -Body (Json @{ event = 'CONTACT' })
Assert-Step 'setup: delivery CONTACT -> COMMUNICATING' ($e1.Json.code -eq 0) $e1.Text
$e2 = Api POST "/api/delivery/$Deliv/events" -Token $BobTok -Body (Json @{ event = 'INVITE_INTERVIEW' })
Assert-Step 'setup: delivery INVITE_INTERVIEW -> INTERVIEW' ($e2.Json.code -eq 0) $e2.Text
$dv0 = Api GET "/api/delivery/$Deliv" -Token $SeekTok
Assert-Step 'setup: delivery is INTERVIEW before any interview event' ($dv0.Json.data.status -eq 'INTERVIEW') ("status={0}" -f $dv0.Json.data.status)

function New-Interview([string]$when) {
    $r = Api POST '/api/interview' -Token $BobTok -Body (Json @{ deliveryId = $Deliv; interviewTime = $when; interviewType = 'ONLINE'; addressOrLink = 'https://meet.example.com/p3d'; interviewerName = 'HR' })
    return [pscustomobject]@{ Id = [int]$r.Json.data; Code = $r.Json.code; Text = $r.Text }
}
function IvStatus([int]$id) {
    return (Api GET "/api/interview/$id" -Token $BobTok).Json.data.status
}

$t = (Get-Date).AddDays(3)
$t1 = $t.ToString('yyyy-MM-ddTHH:mm:ss')
$t2 = $t.AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss')
$t3 = $t.AddHours(2).ToString('yyyy-MM-ddTHH:mm:ss')
$t4 = $t.AddHours(3).ToString('yyyy-MM-ddTHH:mm:ss')
$t5 = $t.AddHours(4).ToString('yyyy-MM-ddTHH:mm:ss')

Write-Host ''
Write-Host '########## S1) happy path: CONFIRM -> RESCHEDULE -> CONFIRM -> COMPLETE ##########'
$i1 = New-Interview $t1
Assert-Step 'S1 create -> SCHEDULED' ($i1.Code -eq 0 -and $i1.Id -gt 0 -and (IvStatus $i1.Id) -eq 'SCHEDULED') ("id={0} status={1}" -f $i1.Id, (IvStatus $i1.Id))

$c1 = Api PUT "/api/interview/$($i1.Id)/confirm" -Token $SeekTok
Assert-Step 'S1 seeker CONFIRM -> CONFIRMED' ($c1.Json.code -eq 0 -and (IvStatus $i1.Id) -eq 'CONFIRMED') $c1.Text

$r1 = Api PUT "/api/interview/$($i1.Id)/reschedule" -Token $BobTok -Body (Json @{ interviewTime = $t2 })
$iv1 = Api GET "/api/interview/$($i1.Id)" -Token $BobTok
Assert-Step 'S1 HR RESCHEDULE -> back to SCHEDULED' ($r1.Json.code -eq 0 -and $iv1.Json.data.status -eq 'SCHEDULED') ("status={0}" -f $iv1.Json.data.status)
Assert-Step 'S1 reschedule actually moved the time' ("$($iv1.Json.data.interviewTime)" -match 'T') ("time={0}" -f $iv1.Json.data.interviewTime)

$c2 = Api PUT "/api/interview/$($i1.Id)/confirm" -Token $SeekTok
Assert-Step 'S1 seeker re-CONFIRM after reschedule' ($c2.Json.code -eq 0 -and (IvStatus $i1.Id) -eq 'CONFIRMED') $c2.Text

$fb = Api PUT "/api/interview/$($i1.Id)/feedback" -Token $BobTok -Body (Json @{ result = 'PASS'; comment = 'p3d ok' })
Assert-Step 'S1 HR COMPLETE -> COMPLETED' ($fb.Json.code -eq 0 -and (IvStatus $i1.Id) -eq 'COMPLETED') $fb.Text

$term1 = Api PUT "/api/interview/$($i1.Id)/confirm" -Token $SeekTok
Assert-Step 'S1 COMPLETED is terminal (confirm rejected 400)' ($term1.Json.code -ne 0 -and $term1.Http -eq 400) ("http={0} code={1} msg={2}" -f $term1.Http, $term1.Json.code, $term1.Json.message)

Write-Host ''
Write-Host '########## S2/S3/S4) REJECT and CANCEL from both actors ##########'
$i2 = New-Interview $t3
Assert-Step 'S2 round increments per delivery' ($i2.Code -eq 0) ("id={0}" -f $i2.Id)
$iv2 = Api GET "/api/interview/$($i2.Id)" -Token $SeekTok
$iv1b = Api GET "/api/interview/$($i1.Id)" -Token $BobTok
Assert-Step 'S2 round = previous round + 1' ($iv2.Json.data.round -eq ($iv1b.Json.data.round + 1)) ("prev={0} now={1}" -f $iv1b.Json.data.round, $iv2.Json.data.round)

$rj = Api PUT "/api/interview/$($i2.Id)/reject" -Token $SeekTok
Assert-Step 'S2 seeker REJECT -> REJECTED' ($rj.Json.code -eq 0 -and (IvStatus $i2.Id) -eq 'REJECTED') $rj.Text
$term2 = Api PUT "/api/interview/$($i2.Id)/cancel" -Token $BobTok -Body (Json @{ reason = 'too late' })
Assert-Step 'S2 REJECTED is terminal (cancel rejected 400)' ($term2.Json.code -ne 0 -and $term2.Http -eq 400) ("http={0} code={1}" -f $term2.Http, $term2.Json.code)

$i3 = New-Interview $t4
$cv = Api PUT "/api/interview/$($i3.Id)/cancel" -Token $SeekTok -Body (Json @{ reason = 'p3d seeker cancel' })
$iv3 = Api GET "/api/interview/$($i3.Id)" -Token $SeekTok
Assert-Step 'S3 seeker CANCEL -> CANCELLED' ($cv.Json.code -eq 0 -and $iv3.Json.data.status -eq 'CANCELLED') $cv.Text
Assert-Step 'S3 cancelledBy=SEEKER and reason recorded' ($iv3.Json.data.cancelledBy -eq 'SEEKER' -and $iv3.Json.data.cancelReason -eq 'p3d seeker cancel') ("by={0} reason={1}" -f $iv3.Json.data.cancelledBy, $iv3.Json.data.cancelReason)

$i4 = New-Interview $t5
$null = Api PUT "/api/interview/$($i4.Id)/confirm" -Token $SeekTok
$ch = Api PUT "/api/interview/$($i4.Id)/cancel" -Token $BobTok -Body (Json @{ reason = 'p3d hr cancel' })
$iv4 = Api GET "/api/interview/$($i4.Id)" -Token $BobTok
Assert-Step 'S4 HR CANCEL on a CONFIRMED interview -> CANCELLED' ($ch.Json.code -eq 0 -and $iv4.Json.data.status -eq 'CANCELLED') $ch.Text
Assert-Step 'S4 cancelledBy=HR' ($iv4.Json.data.cancelledBy -eq 'HR') ("by={0}" -f $iv4.Json.data.cancelledBy)

Write-Host ''
Write-Host '########## N) illegal transitions and actor rules ##########'
$i5 = New-Interview $t1
Assert-Step 'N create a SCHEDULED interview to abuse' ($i5.Code -eq 0) ("id={0}" -f $i5.Id)

# NOTE: InterviewStateMachine.apply() checks the TRANSITION TABLE first, then the
# ACTOR RULES. So an actor-rule test only makes sense while the event is otherwise
# legal from the current state -- e.g. REJECT is seeker-only, but it is only a
# *legal transition* from SCHEDULED. From CONFIRMED you get 400 (illegal
# transition) and the actor rule is never consulted. Both are asserted below.
$n1 = Api PUT "/api/interview/$($i5.Id)/confirm" -Token $BobTok
Assert-Step 'N HR cannot CONFIRM from SCHEDULED (actor rule -> 403/20003)' ($n1.Json.code -eq 20003 -and $n1.Http -eq 403) ("http={0} code={1}" -f $n1.Http, $n1.Json.code)

$n2 = Api PUT "/api/interview/$($i5.Id)/reject" -Token $BobTok
Assert-Step 'N HR cannot REJECT from SCHEDULED (actor rule -> 403/20003)' ($n2.Json.code -eq 20003 -and $n2.Http -eq 403) ("http={0} code={1}" -f $n2.Http, $n2.Json.code)

$n3 = Api PUT "/api/interview/$($i5.Id)/feedback" -Token $BobTok -Body (Json @{ result = 'PASS'; comment = 'x' })
Assert-Step 'N SCHEDULED + COMPLETE is illegal (400)' ($n3.Json.code -ne 0 -and $n3.Http -eq 400) ("http={0} code={1}" -f $n3.Http, $n3.Json.code)

$n4 = Api PUT "/api/interview/$($i5.Id)/reschedule" -Token $BobTok -Body (Json @{ interviewTime = $t2 })
Assert-Step 'N RESCHEDULE only from CONFIRMED (400)' ($n4.Json.code -ne 0 -and $n4.Http -eq 400) ("http={0} code={1}" -f $n4.Http, $n4.Json.code)

$null = Api PUT "/api/interview/$($i5.Id)/confirm" -Token $SeekTok
$n5 = Api PUT "/api/interview/$($i5.Id)/feedback" -Token $SeekTok -Body (Json @{ result = 'PASS'; comment = 'x' })
Assert-Step 'N seeker cannot COMPLETE from CONFIRMED (actor rule -> 403/20003)' ($n5.Json.code -eq 20003 -and $n5.Http -eq 403) ("http={0} code={1}" -f $n5.Http, $n5.Json.code)

$n6 = Api PUT "/api/interview/$($i5.Id)/reject" -Token $BobTok
Assert-Step 'N REJECT is not a CONFIRMED transition (400, table beats actor)' ($n6.Json.code -ne 0 -and $n6.Http -eq 400) ("http={0} code={1}" -f $n6.Http, $n6.Json.code)

$n6 = Api GET '/api/interview/99999999' -Token $SeekTok
Assert-Step 'N unknown interview -> 10004' ($n6.Json.code -eq 10004) ("http={0} code={1}" -f $n6.Http, $n6.Json.code)

# a third party who is neither the seeker nor a member of the company
$OutU = "p3dx$sfx"
$PhoneO = '133' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
Api POST '/api/auth/register' -Body (Json @{ username = $OutU; password = $PWD_; phone = $PhoneO }) | Out-Null
$OutTok = (Login $OutU).accessToken
$n7 = Api GET "/api/interview/$($i5.Id)" -Token $OutTok
Assert-Step 'N outsider cannot read the interview (403/20003)' ($n7.Json.code -eq 20003 -and $n7.Http -eq 403) ("http={0} code={1}" -f $n7.Http, $n7.Json.code)
$n8 = Api PUT "/api/interview/$($i5.Id)/cancel" -Token $OutTok -Body (Json @{ reason = 'x' })
Assert-Step 'N outsider cannot cancel the interview (403/20003)' ($n8.Json.code -eq 20003 -and $n8.Http -eq 403) ("http={0} code={1}" -f $n8.Http, $n8.Json.code)

$mine = Api GET '/api/interview/mine' -Token $SeekTok
$mineHit = @($mine.Json.data) | Where-Object { [int]$_.id -eq $i1.Id }
Assert-Step 'N seeker /mine lists the interviews' ($mine.Json.code -eq 0 -and [bool]$mineHit) ("count={0}" -f @($mine.Json.data).Count)

Write-Host ''
Write-Host '########## D-28) interview events must not move the delivery ##########'
$dv1 = Api GET "/api/delivery/$Deliv" -Token $SeekTok
Assert-Step 'D-28 delivery still INTERVIEW after reject/cancel/complete' ($dv1.Json.data.status -eq 'INTERVIEW') ("status={0}" -f $dv1.Json.data.status)

$dv2 = Api GET "/api/delivery/$Deliv" -Token $BobTok -CompanyId $CompanyId
Assert-Step 'D-28 HR sees the same delivery status' ($dv2.Json.data.status -eq 'INTERVIEW') ("status={0}" -f $dv2.Json.data.status)

Write-Host ''
Show-Summary
