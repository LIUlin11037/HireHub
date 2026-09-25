#requires -Version 5.1
<#
  api-coverage.ps1 -- enumerate EVERY HTTP endpoint declared in the source, then
  report which ones are actually exercised by the verification scripts.

  Why this exists: "everything passes" and "everything is covered" are different
  claims. Scripts only prove what they actually call. This tool answers
  "is there any endpoint nobody ever calls?" with evidence instead of a guess.

  Classification:
    * COVERED    -- some script issues a request to this exact path+shape
    * INTERNAL   -- /internal/** : not routed by the gateway; reached indirectly
                    via Feign from another service during an E2E flow
    * UNCOVERED  -- no script calls it (a real gap worth reporting)

  Usage:  .\scripts\api-coverage.ps1
  Pure ASCII by design.
#>
param(
    [string]$Root = (Join-Path $PSScriptRoot '..')
)

$root = (Resolve-Path $Root).Path

# ---------- 1) collect endpoints from controllers ----------
$controllers = Get-ChildItem -Recurse -Path $root -Filter '*Controller.java' -File |
        Where-Object { $_.FullName -notmatch '\\target\\' }

$endpoints = New-Object System.Collections.Generic.List[object]

foreach ($f in $controllers) {
    $text = Get-Content $f.FullName -Raw

    # class-level base path = the FIRST @RequestMapping in the file
    $classMatches = [regex]::Matches($text, '@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
    $basePath = ''
    if ($classMatches.Count -gt 0) { $basePath = $classMatches[0].Groups[1].Value }

    # method-level verb mappings (@GetMapping / @PostMapping / ...), path optional
    foreach ($m in [regex]::Matches($text, '@(Get|Post|Put|Delete|Patch)Mapping\b\s*(\(([^)]*)\))?')) {
        $verb = $m.Groups[1].Value.ToUpper()
        $arg = $m.Groups[3].Value
        $sub = ''
        if ($arg) {
            $pm = [regex]::Match($arg, '"([^"]*)"')
            if ($pm.Success) { $sub = $pm.Groups[1].Value }
        }
        $endpoints.Add([pscustomobject]@{
            Verb = $verb
            Path = ($basePath + $sub)
            File = $f.Name
        })
    }

    # any ADDITIONAL @RequestMapping(...) beyond the class-level one
    # (some controllers map methods with @RequestMapping too)
    for ($i = 1; $i -lt $classMatches.Count; $i++) {
        $endpoints.Add([pscustomobject]@{
            Verb = 'ANY'
            Path = ($basePath + $classMatches[$i].Groups[1].Value)
            File = $f.Name
        })
    }
}

$endpoints = $endpoints | Sort-Object Path, Verb -Unique

# ---------- 2) load all verification scripts ----------
$self = $MyInvocation.MyCommand.Name
$scriptFiles = Get-ChildItem -Path (Join-Path $root 'scripts') -Filter '*.ps1' -File |
        Where-Object { $_.Name -ne $self -and $_.Name -ne 'e2e-lib.ps1' }

$bodies = @{}
foreach ($s in $scriptFiles) { $bodies[$s.Name] = (Get-Content $s.FullName -Raw) }

# ---------- 3) match each endpoint against the scripts ----------
# A literal path like /api/interview/12/confirm appears in a script as an
# interpolated string. A {placeholder} segment must therefore match any of:
#   $($expr)   -- sub-expression interpolation
#   $Var       -- plain variable interpolation  (e.g. "/api/job/admin/$JobId/offline")
#   {0}        -- -f format placeholder
#   digits     -- a hard-coded id
# NOTE: an earlier version of this script only allowed the first/third/fourth
# forms, which silently UNDER-reported coverage. Always sanity-check a coverage
# tool against endpoints you know are exercised.
function Convert-ToRegex([string]$path) {
    $parts = [regex]::Split($path, '\{[^}]*\}')
    $escaped = $parts | ForEach-Object { [regex]::Escape($_) }
    $joined = ($escaped -join '@@PH@@')
    $joined = $joined.Replace('@@PH@@', '(?:\$\([^)]*\)|\$[A-Za-z_][A-Za-z0-9_]*|\{[^}]*\}|\d+)')
    return $joined
}

$covered = New-Object System.Collections.Generic.List[object]
$internal = New-Object System.Collections.Generic.List[object]
$uncovered = New-Object System.Collections.Generic.List[object]

foreach ($e in $endpoints) {
    $by = @()
    foreach ($name in $bodies.Keys) {
        if ([regex]::IsMatch($bodies[$name], (Convert-ToRegex $e.Path))) { $by += $name }
    }
    $e | Add-Member -NotePropertyName Scripts -NotePropertyValue ($by -join ',') -Force

    if ($e.Path -like '/internal/*') {
        $internal.Add($e)
    } elseif ($by.Count -gt 0) {
        $covered.Add($e)
    } else {
        $uncovered.Add($e)
    }
}

# ---------- 4) report ----------
Write-Host ''
Write-Host ('=' * 78)
Write-Host ("ENDPOINTS: {0}   (COVERED {1} / INTERNAL {2} / UNCOVERED {3})" -f `
        $endpoints.Count, $covered.Count, $internal.Count, $uncovered.Count)
Write-Host ('=' * 78)

Write-Host ''
Write-Host '--- UNCOVERED: no script calls these (real gaps) ---'
if ($uncovered.Count -eq 0) {
    Write-Host '  (none)'
} else {
    foreach ($e in $uncovered) { Write-Host ("  [{0,-6}] {1}   <- {2}" -f $e.Verb, $e.Path, $e.File) }
}

Write-Host ''
Write-Host '--- /internal/** (not routed by the gateway; reached via Feign) ---'
foreach ($e in $internal) { Write-Host ("  [{0,-6}] {1}   <- {2}" -f $e.Verb, $e.Path, $e.File) }

Write-Host ''
Write-Host '--- endpoints hit directly by each script ---'
foreach ($name in ($bodies.Keys | Sort-Object)) {
    $n = ($covered | Where-Object { $_.Scripts -like "*$name*" }).Count
    Write-Host ("  {0,-28} {1}" -f $name, $n)
}

Write-Host ''
Write-Host '--- full endpoint list ---'
foreach ($e in $endpoints) {
    $tag = if ($e.Path -like '/internal/*') { 'INTERNAL' }
           elseif ($e.Scripts) { 'COVERED' }
           else { 'UNCOVERED' }
    Write-Host ("  {0,-10} [{1,-6}] {2,-46} {3}" -f $tag, $e.Verb, $e.Path, $e.Scripts)
}

if ($uncovered.Count -gt 0) { exit 1 }
