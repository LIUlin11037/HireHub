# Apply a SQL file inside the hirehub-mysql container.
#
# The documented pitfall: `Get-Content x.sql | docker exec -i mysql` corrupts UTF-8
# (PowerShell pipeline re-encodes), silently truncating columns near Chinese comments.
# So we copy the file in and let the container's own shell do the redirection.
param(
    [Parameter(Mandatory = $true)][string]$SqlFile,
    [string]$Container = 'hirehub-mysql',
    [string]$User = 'root',
    [string]$Password = 'root'
)

$ErrorActionPreference = 'Stop'
$src = (Resolve-Path $SqlFile).Path
$name = Split-Path $src -Leaf
$dest = "/tmp/$name"

Write-Host ("copy {0} -> {1}:{2}" -f $name, $Container, $dest)
docker cp $src "${Container}:${dest}"

Write-Host 'executing...'
docker exec $Container sh -c "mysql -u$User -p$Password < $dest"
Write-Host ("exit={0}" -f $LASTEXITCODE)
