# HireHub E2E public library: UTF-8 safe HTTP calls + result collection
$script:GW = 'http://localhost:9000'
$script:Steps = [System.Collections.Generic.List[object]]::new()

# Use HttpWebRequest directly: Windows PowerShell 5.1's Invoke-WebRequest cannot
# reliably expose the response body of a 4xx/5xx reply (the stream is already closed).
function Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [int]$CompanyId = 0,
        [string]$Body
    )
    $req = [System.Net.HttpWebRequest]::Create("$script:GW$Path")
    $req.Method = $Method
    $req.Timeout = 30000
    $req.ReadWriteTimeout = 30000
    $req.AllowAutoRedirect = $false
    if ($Token) { $req.Headers.Add('Authorization', "Bearer $Token") }
    if ($CompanyId -gt 0) { $req.Headers.Add('X-Company-Id', "$CompanyId") }
    if ($Body) {
        $req.ContentType = 'application/json; charset=utf-8'
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream()
        $s.Write($bytes, 0, $bytes.Length)
        $s.Close()
    }
    $resp = $null
    try { $resp = $req.GetResponse() }
    catch [System.Net.WebException] { $resp = $_.Exception.Response }
    catch { return [pscustomobject]@{ Http = -1; Text = $_.Exception.Message; Json = $null } }

    if ($null -eq $resp) { return [pscustomobject]@{ Http = -1; Text = 'no response'; Json = $null } }

    $status = [int]$resp.StatusCode
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $txt = $reader.ReadToEnd()
    $reader.Close()
    $resp.Close()
    return [pscustomobject]@{ Http = $status; Text = $txt; Json = (Convert-Json $txt) }
}

function Convert-Json([string]$t) {
    if ([string]::IsNullOrWhiteSpace($t)) { return $null }
    try { return $t | ConvertFrom-Json } catch { return $null }
}

function Assert-Step {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Detail
    )
    $script:Steps.Add([pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail })
    $flag = if ($Ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $flag, $Name, $Detail)
}

function Show-Summary {
    $pass = ($script:Steps | Where-Object { $_.Ok }).Count
    $all = $script:Steps.Count
    Write-Host ''
    Write-Host ("=" * 70)
    Write-Host ("SUMMARY: {0}/{1} passed" -f $pass, $all)
    Write-Host ("=" * 70)
    foreach ($s in $script:Steps) {
        $flag = if ($s.Ok) { 'PASS' } else { 'FAIL' }
        Write-Host ("{0}  {1}" -f $flag, $s.Name)
    }
    if ($pass -ne $all) { exit 1 }
}
