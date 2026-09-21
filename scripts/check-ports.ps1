# Fast TCP port probe. Test-NetConnection takes 1-2s per CLOSED port,
# which makes an 8-port poll slow enough to look like a hang.
function Test-PortOpen {
    param([int]$Port, [string]$Target = '127.0.0.1', [int]$TimeoutMs = 300)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($Target, $Port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne($TimeoutMs)) {
            $client.EndConnect($iar)
            return $true
        }
        return $false
    }
    catch { return $false }
    finally { $client.Close() }
}

if ($MyInvocation.InvocationName -ne '.') {
    $ports = 9000..9007
    $up = @()
    foreach ($p in $ports) { if (Test-PortOpen -Port $p) { $up += $p } }
    if ($up.Count -eq $ports.Count) {
        Write-Host ("ALL UP: {0}" -f ($up -join ','))
    }
    else {
        Write-Host ("UP {0}/8: {1}   DOWN: {2}" -f $up.Count, ($up -join ','), (($ports | Where-Object { $up -notcontains $_ }) -join ','))
    }
}
