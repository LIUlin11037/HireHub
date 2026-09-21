# HireHub service control: start / stop the 8 services as DETACHED processes.
#
# Why WMI (Win32_Process.Create) instead of Start-Process:
# processes created by Start-Process stay inside the caller's Windows job object,
# so when the harness settles or kills this shell, every java child dies with it.
# WMI-created processes are owned by WmiPrvSE, so they survive the shell.
param(
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string]$Action = 'restart',
    [switch]$SkipWait
)

$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$pidFile = Join-Path $logs 'pids.json'

$java = 'E:\jdk21\bin\java.exe'
$services = @(
    @{ name = 'auth'; jar = 'hirehub-auth' },
    @{ name = 'company'; jar = 'hirehub-company' },
    @{ name = 'job'; jar = 'hirehub-job' },
    @{ name = 'resume'; jar = 'hirehub-resume' },
    @{ name = 'delivery'; jar = 'hirehub-delivery' },
    @{ name = 'interview'; jar = 'hirehub-interview' },
    @{ name = 'notification'; jar = 'hirehub-notification' },
    @{ name = 'gateway'; jar = 'hirehub-gateway' }
)
$ports = 9000..9007

function Get-ServicePids {
    $found = @()
    foreach ($p in $ports) {
        $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($c) { $found += $c.OwningProcess }
    }
    return ($found | Sort-Object -Unique)
}

function Stop-Services {
    $pids = Get-ServicePids
    if (Test-Path $pidFile) {
        $saved = Get-Content $pidFile -Raw | ConvertFrom-Json
        foreach ($s in $saved.PSObject.Properties) { $pids += [int]$s.Value }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
    $pids = $pids | Sort-Object -Unique
    if (-not $pids) { Write-Host 'no running service found'; return }
    foreach ($procId in $pids) {
        try {
            $proc = Get-Process -Id $procId -ErrorAction Stop
            if ($proc.ProcessName -ne 'java') { Write-Host ("skip pid {0} ({1})" -f $procId, $proc.ProcessName); continue }
            Stop-Process -Id $procId -Force -ErrorAction Stop
            Write-Host ("stopped {0} (pid {1})" -f $proc.ProcessName, $procId)
        }
        catch { Write-Host ("could not stop pid {0}: {1}" -f $procId, $_.Exception.Message) }
    }
    Start-Sleep -Seconds 3
}

function Start-Services {
    $pids = [ordered]@{}
    foreach ($s in $services) {
        $jar = Join-Path $root ("{0}\target\{0}-1.0.0.jar" -f $s.jar)
        if (-not (Test-Path $jar)) { Write-Host ("MISSING JAR: {0}" -f $jar); continue }
        $out = Join-Path $logs ("{0}.log" -f $s.name)
        # cmd wrapper so stdout/stderr land in the log file
        $cmd = 'cmd.exe /c ""{0}" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar "{1}" > "{2}" 2>&1"' -f $java, $jar, $out
        $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd }
        if ($r.ReturnValue -eq 0) {
            $pids[$s.name] = [int]$r.ProcessId
            Write-Host ("started {0,-13} wrapper-pid {1}" -f $s.name, $r.ProcessId)
        }
        else { Write-Host ("FAILED to start {0} (ReturnValue={1})" -f $s.name, $r.ReturnValue) }
        Start-Sleep -Seconds 2
    }
    $pids | ConvertTo-Json | Set-Content -Path $pidFile -Encoding UTF8
}

function Wait-Ports {
    Write-Host '=== waiting for ports ==='
    foreach ($p in $ports) {
        $ok = $false
        for ($i = 0; $i -lt 75; $i++) {
            if ((Test-NetConnection 127.0.0.1 -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded) { $ok = $true; break }
            Start-Sleep -Seconds 2
        }
        Write-Host ("port {0} = {1}" -f $p, $ok)
    }
}

switch ($Action) {
    'stop' { Stop-Services }
    'start' { Start-Services; if (-not $SkipWait) { Wait-Ports } }
    'restart' { Stop-Services; Start-Services; if (-not $SkipWait) { Wait-Ports } }
    'status' {
        foreach ($p in $ports) {
            Write-Host ("port {0} = {1}" -f $p, (Test-NetConnection 127.0.0.1 -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded)
        }
    }
}
Write-Host 'DONE'
