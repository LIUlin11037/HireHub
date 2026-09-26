#requires -Version 5.1
<#
  HireHub 端到端验证脚本 —— 按 docs/端到端验证.md 的顺序调用全部对外接口。
  用法：
    .\scripts\e2e-verify.ps1                                  # 文档原值：alice / bob / 913100001234567896
    .\scripts\e2e-verify.ps1 -Alice alice2 -Bob bob2 -Fresh   # 全新身份 + 自动计算合法信用代码
#>
param(
    [string]$Alice = 'alice',
    [string]$Bob = 'bob',
    [string]$CreditCode = '913100001234567896',
    [string]$CompanyName = '某某科技',
    [string]$PhoneAlice = '13800000001',
    [string]$PhoneBob = '13800000002',
    [switch]$Fresh
)

. "$PSScriptRoot\e2e-lib.ps1"

$ID_CARD_OK = '110101199003078515'   # 校验位真算法可过
$ID_CARD_BAD = '110101199003078510'  # 校验位错误

# 全新运行时：根据 GB 32100 现算一个合法信用代码，避免「该企业已入驻」
if ($Fresh) {
    $suffix = Get-Random -Minimum 1000 -Maximum 9999
    $Alice = "$Alice$suffix"
    $Bob = "$Bob$suffix"
    $ALPHABET = '0123456789ABCDEFGHJKLMNPQRTUWXY'
    $W = @(1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)
    $base17 = '91330100' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()
    $base17 = $base17.Substring(0, 17)
    $sum = 0
    for ($i = 0; $i -lt 17; $i++) { $sum += $ALPHABET.IndexOf($base17[$i]) * $W[$i] }
    $CreditCode = $base17 + $ALPHABET[(31 - $sum % 31) % 31]
    $PhoneAlice = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    $PhoneBob = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    Write-Host ("fresh credit code = {0} / phones = {1},{2}" -f $CreditCode, $PhoneAlice, $PhoneBob)
}

Write-Host ''
Write-Host '########## Phase 0：登录 / 注册 ##########'

# ---------- ① 求职者 alice ----------
$reg = Api POST '/api/auth/register' -Body ("{{""username"":""{0}"",""password"":""123456ab"",""phone"":""{1}""}}" -f $Alice, $PhoneAlice)
$regOk = ($reg.Json.code -eq 0) -or ($reg.Text -match '已存在')
Assert-Step "① alice 注册 ($Alice)" $regOk $reg.Text

$login = Api POST '/api/auth/login' -Body ("{{""username"":""{0}"",""password"":""123456ab""}}" -f $Alice)
$ALICE = $login.Json.data.accessToken
Assert-Step '① alice 登录 → accessToken' ([bool]$ALICE) ("userId={0} roles={1}" -f $login.Json.data.userId, ($login.Json.data.roles -join ','))

$me = Api GET '/api/auth/me' -Token $ALICE
Assert-Step '① alice /me' ($me.Json.code -eq 0) $me.Text

# 实名：先负面（编造校验位）再正面
$bad = Api POST '/api/auth/real-name' -Token $ALICE -Body ("{{""realName"":""李小明"",""idCard"":""{0}""}}" -f $ID_CARD_BAD)
Assert-Step '① alice 实名[负面] 校验位错误被拒 (400)' ($bad.Json.code -ne 0 -and $bad.Http -eq 400) ("http={0} code={1} msg={2}" -f $bad.Http, $bad.Json.code, $bad.Json.message)

$rn = Api POST '/api/auth/real-name' -Token $ALICE -Body ("{{""realName"":""李小明"",""idCard"":""{0}""}}" -f $ID_CARD_OK)
Assert-Step '① alice 实名[正面] 校验位真算法通过' ($rn.Json.code -eq 0) $rn.Text

$rns = Api GET '/api/auth/real-name/status' -Token $ALICE
Assert-Step '① alice realNameStatus=1' ($rns.Json.data -eq 1) $rns.Text

# 简历：幂等 —— 已存在则复用
$resumeBody = '{"name":"李小明","phone":"13800000001","expectCity":"杭州","expectPosition":"Java","expectSalaryMin":20000,"expectSalaryMax":35000}'
$RESUME_ID = 0
$mine = Api GET '/api/resume/mine' -Token $ALICE
if ($mine.Json.code -eq 0 -and $mine.Json.data.Count -gt 0) {
    $RESUME_ID = $mine.Json.data[0].id
    Assert-Step '① alice 建简历（已存在，复用）' $true ("resumeId={0}" -f $RESUME_ID)
}
else {
    $cr = Api POST '/api/resume' -Token $ALICE -Body $resumeBody
    $RESUME_ID = $cr.Json.data
    Assert-Step '① alice 建简历' ($cr.Json.code -eq 0 -and $RESUME_ID -gt 0) ("resumeId={0}" -f $RESUME_ID)
}
$rget = Api GET ("/api/resume/{0}" -f $RESUME_ID) -Token $ALICE
Assert-Step '① alice 简历详情' ($rget.Json.code -eq 0) ("name={0} expectCity={1}" -f $rget.Json.data.name, $rget.Json.data.expectCity)

# ---------- ② HR bob ----------
$reg = Api POST '/api/auth/register' -Body ("{{""username"":""{0}"",""password"":""123456ab"",""phone"":""{1}""}}" -f $Bob, $PhoneBob)
Assert-Step "② bob 注册 ($Bob)" (($reg.Json.code -eq 0) -or ($reg.Text -match '已存在')) $reg.Text

$login = Api POST '/api/auth/login' -Body ("{{""username"":""{0}"",""password"":""123456ab""}}" -f $Bob)
$BOB = $login.Json.data.accessToken
Assert-Step '② bob 登录 → accessToken' ([bool]$BOB) ("userId={0}" -f $login.Json.data.userId)

$rn = Api POST '/api/auth/real-name' -Token $BOB -Body ("{{""realName"":""王大壮"",""idCard"":""{0}""}}" -f $ID_CARD_OK)
Assert-Step '② bob 实名' ($rn.Json.code -eq 0) $rn.Text

# ---------- ③ 管理员 admin ----------
$login = Api POST '/api/auth/login' -Body '{"username":"admin","password":"admin123"}'
$ADMIN = $login.Json.data.accessToken
Assert-Step '③ admin 登录' (($login.Json.code -eq 0) -and [bool]$ADMIN) ("roles={0}" -f ($login.Json.data.roles -join ','))

# ---------- 企业 ----------
$COMPANY_ID = 0
$cc = Api POST '/api/company' -Token $BOB -Body ("{{""name"":""{0}"",""creditCode"":""{1}"",""industry"":""互联网"",""scale"":""100-499人"",""city"":""杭州""}}" -f $CompanyName, $CreditCode)
if ($cc.Json.code -eq 0) {
    $COMPANY_ID = $cc.Json.data
    Assert-Step '② bob 建企业 → companyId（创建者自动 OWNER）' ($COMPANY_ID -gt 0) ("companyId={0}" -f $COMPANY_ID)
}
elseif ($cc.Text -match '已入驻') {
    # 复用上一轮已入驻的企业
    Assert-Step '② bob 建企业[幂等] 信用代码唯一约束生效' $true $cc.Text
    $pending = Api GET '/api/company/admin/list' -Token $ADMIN
    $hit = $pending.Json.data | Where-Object { $_.creditCode -eq $CreditCode } | Select-Object -First 1
    if ($hit) { $COMPANY_ID = $hit.id }
    Assert-Step '② 复用已入驻企业 companyId' ($COMPANY_ID -gt 0) ("companyId={0}" -f $COMPANY_ID)
}
else {
    Assert-Step '② bob 建企业' $false $cc.Text
}

# 非法信用代码 → 真算法拦截
$badCo = Api POST '/api/company' -Token $BOB -Body '{"name":"乱编公司","creditCode":"913100001234567890","industry":"互联网","city":"杭州"}'
Assert-Step '② 建企业[负面] 信用代码校验位错误被拒 (400)' ($badCo.Json.code -ne 0 -and $badCo.Http -eq 400) ("http={0} code={1} msg={2}" -f $badCo.Http, $badCo.Json.code, $badCo.Json.message)

$members = Api GET ("/api/company/{0}/members" -f $COMPANY_ID) -Token $BOB
$owner = $members.Json.data | Where-Object { $_.role -eq 'OWNER' } | Select-Object -First 1
Assert-Step '② 企业成员 OWNER 已建立' ([bool]$owner) $members.Text

$cget = Api GET ("/api/company/{0}" -f $COMPANY_ID) -Token $BOB
Assert-Step '② 企业详情' ($cget.Json.code -eq 0) ("name={0} verifyStatus={1}" -f $cget.Json.data.name, $cget.Json.data.verifyStatus)

Write-Host ''
Write-Host '########## Phase 1：提交企业认证 ##########'
$sv = Api POST ("/api/company/{0}/verify" -f $COMPANY_ID) -Token $BOB -Body '{"legalPersonName":"王大壮"}'
Assert-Step '② bob 提交认证（Mock 三要素核验）' ($sv.Json.code -eq 0) $sv.Text

$vs = Api GET ("/api/company/{0}/verify-status" -f $COMPANY_ID) -Token $BOB
Assert-Step '③ 提交后仍为待审核 verifyStatus=0' ($vs.Json.data -eq 0) $vs.Text

Write-Host ''
Write-Host '########## Phase 2：未认证前上线职位应被拦（三重校验） ##########'
$jobBody = '{"companyId":' + $COMPANY_ID + ',"title":"高级 Java 工程师","skills":"[\"Java\",\"Spring Boot\"]","city":"杭州","salaryMin":25000,"salaryMax":40000,"education":"本科","experience":"3-5年","headcount":2,"jobType":"全职"}'
$jd = Api POST '/api/job/draft' -Token $BOB -Body $jobBody
$JOB_ID = $jd.Json.data
Assert-Step '④ bob 建职位草稿 → jobId' ($jd.Json.code -eq 0 -and $JOB_ID -gt 0) ("jobId={0}" -f $JOB_ID)

$pub1 = Api POST ("/api/job/{0}/publish" -f $JOB_ID) -Token $BOB
Assert-Step '④ 上线[负面] 企业未认证被拦 (400)' ($pub1.Json.code -ne 0 -and $pub1.Http -eq 400 -and $pub1.Text -match '认证') ("http={0} code={1} msg={2}" -f $pub1.Http, $pub1.Json.code, $pub1.Json.message)

$pubA = Api POST ("/api/job/{0}/publish" -f $JOB_ID) -Token $ALICE
Assert-Step '④ 上线[负面] 非企业成员被拒 (403)' ($pubA.Json.code -eq 20003 -and $pubA.Http -eq 403) ("http={0} code={1} msg={2}" -f $pubA.Http, $pubA.Json.code, $pubA.Json.message)

Write-Host ''
Write-Host '########## Phase 3：管理员审核企业 ##########'
$list = Api GET '/api/company/admin/list' -Token $ADMIN
$found = $list.Json.data | Where-Object { $_.id -eq $COMPANY_ID } | Select-Object -First 1
Assert-Step '③ admin 待审核列表含该企业' ([bool]$found) ("count={0}" -f @($list.Json.data).Count)

$notAdmin = Api GET '/api/company/admin/list' -Token $ALICE
Assert-Step '③ 审核接口[负面] 非管理员被拒 (403)' ($notAdmin.Json.code -eq 20003 -and $notAdmin.Http -eq 403) ("http={0} code={1} msg={2}" -f $notAdmin.Http, $notAdmin.Json.code, $notAdmin.Json.message)

$av = Api POST ("/api/company/admin/{0}/verify" -f $COMPANY_ID) -Token $ADMIN -Body '{"approve":true}'
Assert-Step '③ admin 审核通过' ($av.Json.code -eq 0) $av.Text

$vs = Api GET ("/api/company/{0}/verify-status" -f $COMPANY_ID) -Token $BOB
Assert-Step '③ 审核后 verifyStatus=1' ($vs.Json.data -eq 1) $vs.Text

Write-Host ''
Write-Host '########## Phase 4：上线职位（三重校验通过） ##########'
$pub2 = Api POST ("/api/job/{0}/publish" -f $JOB_ID) -Token $BOB
Assert-Step '④ bob 上线职位（实名+归属+企业已认证）' ($pub2.Json.code -eq 0) $pub2.Text

$jget = Api GET ("/api/job/{0}" -f $JOB_ID) -Token $BOB
Assert-Step '④ 职位状态=1 招聘中' ($jget.Json.data.status -eq 1) ("status={0} title={1}" -f $jget.Json.data.status, $jget.Json.data.title)

Write-Host ''
Write-Host '########## Phase 5：投递（幂等 + 快照） ##########'
$d1 = Api POST '/api/delivery' -Token $ALICE -Body ("{{""jobId"":{0},""resumeId"":{1}}}" -f $JOB_ID, $RESUME_ID)
$DELIVERY_ID = $d1.Json.data
Assert-Step '⑤ alice 投递 → deliveryId' ($d1.Json.code -eq 0 -and $DELIVERY_ID -gt 0) ("deliveryId={0}" -f $DELIVERY_ID)

$d2 = Api POST '/api/delivery' -Token $ALICE -Body ("{{""jobId"":{0},""resumeId"":{1}}}" -f $JOB_ID, $RESUME_ID)
Assert-Step '⑤ 重复投递幂等 → 同一 deliveryId' ($DELIVERY_ID -gt 0 -and $d2.Json.code -eq 0 -and $d2.Json.data -eq $DELIVERY_ID) ("first={0} again={1}" -f $DELIVERY_ID, $d2.Json.data)

$dmine = Api GET '/api/delivery/mine' -Token $ALICE
Assert-Step '⑤ 我的投递列表' ($dmine.Json.code -eq 0) ("count={0}" -f @($dmine.Json.data).Count)

$snap = Api GET ("/api/delivery/{0}/resume-snapshot" -f $DELIVERY_ID) -Token $ALICE
Assert-Step '⑤ 简历快照已留存（D-26）' ($snap.Json.code -eq 0 -and $snap.Text -match '李小明') ("snapshotTime={0}" -f $snap.Json.data.snapshotTime)

Write-Host ''
Write-Host '########## Phase 6：HR 处理投递 → 面试 → Offer ##########'
$recv = Api GET '/api/delivery/received' -Token $BOB -CompanyId $COMPANY_ID
$recvCount = @($recv.Json.data | Where-Object { $_ }).Count
Assert-Step '⑥ HR 收到投递（X-Company-Id）' ($recv.Json.code -eq 0 -and $recvCount -ge 1) ("count={0}" -f $recvCount)

$recvNo = Api GET '/api/delivery/received' -Token $BOB
Assert-Step '⑥ 缺 X-Company-Id → 400（网关/服务端校验）' ($recvNo.Http -eq 400) ("http={0} body={1}" -f $recvNo.Http, $recvNo.Text)

$dg = Api GET ("/api/delivery/{0}" -f $DELIVERY_ID) -Token $BOB
Assert-Step '⑥ HR 查看详情 PENDING→VIEWED' ($dg.Json.data.status -eq 'VIEWED') ("status={0}" -f $dg.Json.data.status)

$ev = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $BOB -Body '{"event":"CONTACT"}'
Assert-Step '⑥ 事件 CONTACT → COMMUNICATING' ($ev.Json.code -eq 0) $ev.Text

$ev = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $BOB -Body '{"event":"INVITE_INTERVIEW"}'
Assert-Step '⑥ 事件 INVITE_INTERVIEW → INTERVIEW' ($ev.Json.code -eq 0) $ev.Text

$iv = Api POST '/api/interview' -Token $BOB -Body ("{{""deliveryId"":{0},""interviewTime"":""2026-01-15T10:00:00"",""interviewType"":""线上"",""interviewerName"":""王大壮""}}" -f $DELIVERY_ID)
$INTERVIEW_ID = $iv.Json.data
Assert-Step '⑥ 发起面试邀约 → interviewId' ($iv.Json.code -eq 0 -and $INTERVIEW_ID -gt 0) ("interviewId={0}" -f $INTERVIEW_ID)

$ivg = Api GET ("/api/interview/{0}" -f $INTERVIEW_ID) -Token $BOB
Assert-Step '⑥ 面试详情（SCHEDULED）' ($ivg.Json.data.status -eq 'SCHEDULED') ("status={0} round={1}" -f $ivg.Json.data.status, $ivg.Json.data.round)

Write-Host ''
Write-Host '########## Phase 7：状态机权限（越权 403 / 非法迁移 400） ##########'
$of = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $ALICE -Body '{"event":"SEND_OFFER"}'
Assert-Step '⑦ 越权事件[负面] 求职者发 SEND_OFFER (403)' ($of.Json.code -eq 20003 -and $of.Http -eq 403) ("http={0} code={1} msg={2}" -f $of.Http, $of.Json.code, $of.Json.message)

$of = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $BOB -Body '{"event":"SEND_OFFER"}'
Assert-Step '⑥ 事件 SEND_OFFER → OFFER' ($of.Json.code -eq 0) $of.Text

$dfin = Api GET ("/api/delivery/{0}" -f $DELIVERY_ID) -Token $ALICE
Assert-Step '⑥ 投递终态校验 status=OFFER' ($dfin.Json.data.status -eq 'OFFER') ("status={0}" -f $dfin.Json.data.status)

$badEv = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $BOB -Body '{"event":"NO_SUCH_EVENT"}'
Assert-Step '⑦ 非法事件名被拒 (400)' ($badEv.Json.code -ne 0 -and $badEv.Http -eq 400 -and $badEv.Text -match '非法事件') ("http={0} code={1} msg={2}" -f $badEv.Http, $badEv.Json.code, $badEv.Json.message)

$cancel = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $ALICE -Body '{"event":"CANCEL"}'
Assert-Step '⑦ 求职者拒 Offer：OFFER+CANCEL → CANCELLED' ($cancel.Json.code -eq 0) $cancel.Text

$cancel2 = Api POST ("/api/delivery/{0}/events" -f $DELIVERY_ID) -Token $ALICE -Body '{"event":"CANCEL"}'
Assert-Step '⑦ 非法迁移[负面] CANCELLED 终态再发事件被拒 (400)' ($cancel2.Json.code -ne 0 -and $cancel2.Http -eq 400 -and $cancel2.Text -match '非法状态迁移') ("http={0} code={1} msg={2}" -f $cancel2.Http, $cancel2.Json.code, $cancel2.Json.message)

$dfin = Api GET ("/api/delivery/{0}" -f $DELIVERY_ID) -Token $ALICE
Assert-Step '⑦ 终态 status=CANCELLED' ($dfin.Json.data.status -eq 'CANCELLED') ("status={0}" -f $dfin.Json.data.status)

Write-Host ''
Write-Host '########## Phase 8：网关鉴权 ##########'
$noTok = Api GET '/api/auth/me'
Assert-Step '⑧ 无 token → 401' ($noTok.Http -eq 401) ("http={0} body={1}" -f $noTok.Http, $noTok.Text)

$badTok = Api GET '/api/auth/me' -Token 'not-a-jwt'
Assert-Step '⑧ 伪造 token → 401' ($badTok.Http -eq 401) ("http={0} body={1}" -f $badTok.Http, $badTok.Text)

$logoff = Api GET '/api/notification/list' -Token $ALICE
Assert-Step '⑧ 通知列表可访问' ($logoff.Json.code -eq 0) $logoff.Text
$unread = Api GET '/api/notification/unread-count' -Token $ALICE
Assert-Step '⑧ 未读数可访问' ($unread.Json.code -eq 0) $unread.Text

Write-Host ''
Write-Host ("IDS: companyId={0} jobId={1} resumeId={2} deliveryId={3} interviewId={4}" -f $COMPANY_ID, $JOB_ID, $RESUME_ID, $DELIVERY_ID, $INTERVIEW_ID)

Show-Summary
