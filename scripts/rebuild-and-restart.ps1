# Rebuild all HireHub modules and restart the 8 services with logs captured under .\logs
param(
    [switch]$SkipBuild,
    [switch]$SkipRestart
)

$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$env:JAVA_HOME = 'E:\jdk21'
$mvn = 'F:\apache-maven-3.9.12\bin\mvn.cmd'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

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

if (-not $SkipBuild) {
    Write-Host '=== mvn clean package ==='
    & $mvn -B -DskipTests clean package 2>&1 | Tee-Object -FilePath (Join-Path $logs 'build.log') | Select-Object -Last 40
    if ($LASTEXITCODE -ne 0) {
        Write-Host "BUILD FAILED (exit $LASTEXITCODE) - see logs\build.log"
        exit 2
    }
    Write-Host 'BUILD OK'
}

if (-not $SkipRestart) {
    Write-Host '=== stopping services on 9000-9007 ==='
    $owners = Get-NetTCPConnection -State Listen -LocalPort $ports -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($pid_ in $owners) {
        try {
            $p = Get-Process -Id $pid_ -ErrorAction Stop
            Stop-Process -Id $pid_ -Force -ErrorAction Stop
            Write-Host ("stopped pid {0} ({1})" -f $pid_, $p.ProcessName)
        }
        catch { Write-Host ("could not stop pid {0}: {1}" -f $pid_, $_.Exception.Message) }
    }
    Start-Sleep -Seconds 4

    Write-Host '=== starting services ==='
    foreach ($s in $services) {
        $jar = Join-Path $root ("{0}\target\{0}-1.0.0.jar" -f $s.jar)
        if (-not (Test-Path $jar)) { Write-Host ("MISSING JAR: {0}" -f $jar); continue }
        $out = Join-Path $logs ("{0}.log" -f $s.name)
        $err = Join-Path $logs ("{0}.err.log" -f $s.name)
        $p = Start-Process -FilePath $java `
            -ArgumentList @('-Dfile.encoding=UTF-8', '-Duser.timezone=Asia/Shanghai', '-jar', $jar) `
            -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
        Write-Host ("started {0} pid {1}" -f $s.name, $p.Id)
        Start-Sleep -Seconds 2
    }

    Write-Host '=== waiting for ports ==='
    foreach ($p in $ports) {
        $ok = $false
        for ($i = 0; $i -lt 60; $i++) {
            if ((Test-NetConnection 127.0.0.1 -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded) { $ok = $true; break }
            Start-Sleep -Seconds 2
        }
        Write-Host ("port {0} = {1}" -f $p, $ok)
    }
}

Write-Host 'DONE'
