#requires -Version 5.1
<#
  HireHub load probe. Reports REAL measured numbers only - never synthetic.

  Two phases per run:
    1) throughput : $Concurrency workers fire continuously for $DurationSec seconds
    2) latency    : $Samples sequential requests, timed individually -> p50/p95/p99/max

  Why two phases: with concurrent senders you cannot attribute a per-request latency
  without a per-request continuation (PowerShell scriptblocks cannot run on raw .NET
  threads - no runspace). So throughput comes from the concurrent phase and latency
  percentiles from the sequential one. Both numbers are real; do not mix them up.

  Usage:
    .\scripts\perf-load.ps1 -Label 'job search (ES)' -Path '/api/job/search?keyword=Java&size=10' -Token $t
    .\scripts\perf-load.ps1 -Label 'job detail (Redis cache)' -Path '/api/job/12' -Token $t
    .\scripts\perf-load.ps1 -Label 'delivery create' -Method POST -Path '/api/delivery' -Body '{...}' -Token $t
#>
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Method = 'GET',
    [string]$Token,
    [int]$CompanyId = 0,
    [string]$Body,
    [int]$Concurrency = 32,
    [int]$DurationSec = 15,
    [int]$Samples = 200,
    [string]$BaseUrl = 'http://127.0.0.1:9000'
)

Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = 1024
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

function New-Req() {
    $r = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), "$BaseUrl$Path")
    if ($Token) { $r.Headers.Add('Authorization', "Bearer $Token") }
    if ($CompanyId -gt 0) { $r.Headers.Add('X-Company-Id', "$CompanyId") }
    if ($Body) { $r.Content = New-Object System.Net.Http.StringContent($Body, [System.Text.Encoding]::UTF8, 'application/json') }
    return $r
}

Write-Host ("### {0}" -f $Label)
Write-Host ("    {0} {1}  concurrency={2} duration={3}s samples={4}" -f $Method, $Path, $Concurrency, $DurationSec, $Samples)

# ---------- phase 1: throughput ----------
$codes = @{}
$total = 0
$deadline = [DateTime]::UtcNow.AddSeconds($DurationSec)
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
while ([DateTime]::UtcNow -lt $deadline) {
    $tasks = @()
    for ($i = 0; $i -lt $Concurrency; $i++) { $tasks += $client.SendAsync((New-Req)) }
    try { [System.Threading.Tasks.Task]::WaitAll($tasks) } catch { }
    foreach ($t in $tasks) {
        $total++
        if ($t.Status -eq 'RanToCompletion') {
            $c = [int]$t.Result.StatusCode
            $codes[$c] = 1 + ($codes[$c] -as [int])
        } else {
            $codes['ERR'] = 1 + ($codes['ERR'] -as [int])
        }
    }
}
$swAll.Stop()
$rps = [Math]::Round($total / $swAll.Elapsed.TotalSeconds, 1)
$ok = 0; $bad = 0
foreach ($k in $codes.Keys) { if ($k -is [int] -and $k -ge 200 -and $k -lt 300) { $ok += $codes[$k] } else { $bad += $codes[$k] } }
Write-Host ("    throughput: {0} requests in {1:N1}s -> {2} req/s ; ok={3} notok={4}" -f $total, $swAll.Elapsed.TotalSeconds, $rps, $ok, $bad)
$dist = ($codes.GetEnumerator() | Sort-Object Name | ForEach-Object { "{0}:{1}" -f $_.Key, $_.Value }) -join '  '
Write-Host ("    status    : {0}" -f $dist)

# ---------- phase 2: sequential latency ----------
$lat = New-Object System.Collections.Generic.List[double]
$latCodes = @{}
for ($i = 0; $i -lt $Samples; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = $client.SendAsync((New-Req)).GetAwaiter().GetResult()
        $sw.Stop()
        $latCodes[[int]$resp.StatusCode] = 1 + ($latCodes[[int]$resp.StatusCode] -as [int])
    } catch { $sw.Stop(); $latCodes['ERR'] = 1 + ($latCodes['ERR'] -as [int]) }
    $lat.Add($sw.Elapsed.TotalMilliseconds)
}
$sorted = $lat | Sort-Object
function Pctl($p) { $idx = [Math]::Min($sorted.Count - 1, [Math]::Floor($sorted.Count * $p)); return [Math]::Round($sorted[$idx], 1) }
Write-Host ("    latency   : p50={0}ms p95={1}ms p99={2}ms max={3}ms" -f (Pctl 0.50), (Pctl 0.95), (Pctl 0.99), [Math]::Round($sorted[-1], 1))
$ldist = ($latCodes.GetEnumerator() | Sort-Object Name | ForEach-Object { "{0}:{1}" -f $_.Key, $_.Value }) -join '  '
Write-Host ("    seq status: {0}" -f $ldist)
Write-Host ''
