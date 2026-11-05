param(
    [Parameter(Mandatory = $true)][string]$Commit,
    [Parameter(Mandatory = $true)][long[]]$CiRunIds,
    [string]$Tag = 'v0.10.3-beta.2',
    [int]$VersionCode = 75,
    [string]$VersionName = '0.10.3-beta.2'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repoDir = Split-Path -Parent $PSScriptRoot
$artifactDir = Join-Path (Split-Path -Parent $repoDir) "release\v$VersionName"
$apiRoot = 'https://api.github.com/repos/paraecologia-arch/XENOWAVE'

$apkName = "XENOWAVE-$VersionName-core.apk"
$patchName = "XENOWAVE-0.10.3-beta.1-to-$VersionName.xnd"
$files = @($apkName, 'update.json', 'SHA256SUMS.txt', $patchName)
$notesPath = Join-Path $artifactDir 'release-notes-public.md'
if (-not (Test-Path -LiteralPath $notesPath)) { throw 'Missing release-notes-public.md.' }
$notes = [IO.File]::ReadAllText($notesPath, [Text.Encoding]::UTF8)

$expected = @{}
foreach ($name in $files) {
    $file = Get-Item -LiteralPath (Join-Path $artifactDir $name)
    $expected[$name] = @{
        size = $file.Length
        hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$metadata = Get-Content -LiteralPath (Join-Path $artifactDir 'update.json') -Encoding UTF8 -Raw | ConvertFrom-Json
if ($metadata.versionCode -ne $VersionCode -or
    $metadata.versionName -ne $VersionName -or
    $metadata.applicationId -ne 'com.dd3boh.xenowave' -or
    $metadata.sha256 -ne $expected[$apkName].hash -or
    $metadata.sizeBytes -ne $expected[$apkName].size) {
    throw 'update.json diverges from the signed APK.'
}
if (@($metadata.deltas).Count -ne 1 -or
    $metadata.deltas[0].patchName -ne $patchName -or
    $metadata.deltas[0].patchSha256 -ne $expected[$patchName].hash -or
    $metadata.deltas[0].patchSizeBytes -ne $expected[$patchName].size -or
    $metadata.deltas[0].fromVersionCode -ne 74 -or
    $metadata.deltas[0].fromApkSha256 -ne 'c2f0d8256909380b2967510ab8a2b2106f8297a04b92b5f93ace7837dfbf0c92') {
    throw 'Delta metadata diverges from the published 74 base.'
}

$previousPrompt = $env:GIT_TERMINAL_PROMPT
$headers = $null
$token = $null
$credentialLines = $null
try {
    $env:GIT_TERMINAL_PROMPT = '0'
    $credentialLines = "protocol=https`nhost=github.com`n`n" | git -C $repoDir credential fill 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'GitHub local authentication unavailable.' }
    $passwordLine = @($credentialLines | Where-Object { $_.StartsWith('password=') })
    if ($passwordLine.Count -ne 1) { throw 'GitHub credential missing.' }
    $token = $passwordLine[0].Substring(9)
    $headers = @{
        Authorization = 'Bearer ' + $token
        Accept = 'application/vnd.github+json'
        'User-Agent' = 'XENOWAVE-Public-Release'
        'X-GitHub-Api-Version' = '2022-11-28'
    }

    foreach ($runId in $CiRunIds) {
        $run = Invoke-RestMethod -Uri "$apiRoot/actions/runs/$runId" -Headers $headers -TimeoutSec 45
        if ($run.head_sha -ne $Commit -or $run.status -ne 'completed' -or $run.conclusion -ne 'success') {
            throw "CI is not green for the commit: $runId"
        }
        Write-Output ("CI approved: " + $run.name + " (" + $runId + ")")
    }

    $tagRef = $null
    try {
        $tagRef = Invoke-RestMethod -Uri "$apiRoot/git/ref/tags/$Tag" -Headers $headers -TimeoutSec 45
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
    }
    if ($null -ne $tagRef) {
        $tagObject = $tagRef.object
        if ($tagObject.type -eq 'tag') {
            $tagObject = (Invoke-RestMethod -Uri "$apiRoot/git/tags/$($tagObject.sha)" -Headers $headers -TimeoutSec 45).object
        }
        if ($tagObject.type -ne 'commit' -or $tagObject.sha -ne $Commit) {
            throw 'Existing tag points to another commit.'
        }
    }

    $releases = Invoke-RestMethod -Uri "$apiRoot/releases?per_page=100" -Headers $headers -TimeoutSec 45
    $matching = @($releases | Where-Object { $_.tag_name -eq $Tag })
    if ($matching.Count -gt 1) { throw 'Unexpected duplicate release.' }
    if ($matching.Count -eq 0) {
        $payload = @{
            tag_name = $Tag
            target_commitish = $Commit
            name = "XENOWAVE $VersionName - baixar e compartilhar sem GitHub"
            body = $notes
            draft = $true
            prerelease = $true
            make_latest = 'false'
        } | ConvertTo-Json -Depth 5
        $release = Invoke-RestMethod -Method Post -Uri "$apiRoot/releases" -Headers $headers `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 60
        Write-Output ('Draft created: ' + $release.id)
    } else {
        $release = $matching[0]
        if (-not $release.prerelease -or $release.tag_name -ne $Tag) {
            throw 'Existing release is incompatible.'
        }
        Write-Output ('Existing release: ' + $release.id)
    }

    foreach ($name in $files) {
        $assets = Invoke-RestMethod -Uri "$apiRoot/releases/$($release.id)/assets?per_page=100" -Headers $headers -TimeoutSec 45
        $existing = @($assets | Where-Object { $_.name -eq $name })
        if ($existing.Count -gt 1) { throw ('Duplicate asset: ' + $name) }
        if ($existing.Count -eq 1) {
            if ($existing[0].state -ne 'uploaded' -or
                $existing[0].size -ne $expected[$name].size -or
                $existing[0].digest -ne ('sha256:' + $expected[$name].hash)) {
                throw ('Existing asset diverges and was preserved: ' + $name)
            }
            Write-Output ('Existing asset checked: ' + $name)
            continue
        }
        if (-not $release.draft) {
            throw ('Release is public with a missing asset; stop to preserve it: ' + $name)
        }
        $uploadUrl = 'https://uploads.github.com/repos/paraecologia-arch/XENOWAVE/releases/' +
            $release.id + '/assets?name=' + [Uri]::EscapeDataString($name)
        $mime = switch ([IO.Path]::GetExtension($name)) {
            '.apk' { 'application/vnd.android.package-archive' }
            '.json' { 'application/json' }
            '.txt' { 'text/plain' }
            default { 'application/octet-stream' }
        }
        $uploaded = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers -ContentType $mime `
            -InFile (Join-Path $artifactDir $name) -TimeoutSec 180
        if ($uploaded.state -ne 'uploaded' -or
            $uploaded.size -ne $expected[$name].size -or
            $uploaded.digest -ne ('sha256:' + $expected[$name].hash)) {
            throw ('Asset verification failed: ' + $name)
        }
        Write-Output ('Asset uploaded and checked: ' + $name)
    }

    if ($release.draft) {
        $publishPayload = @{
            draft = $false
            prerelease = $true
            make_latest = 'false'
            body = $notes
        } | ConvertTo-Json -Depth 4
        $release = Invoke-RestMethod -Method Patch -Uri "$apiRoot/releases/$($release.id)" -Headers $headers `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($publishPayload)) -TimeoutSec 60
    }
    if ($release.draft -or -not $release.prerelease) { throw 'Publication was not confirmed.' }
    Write-Output ('Published: ' + $release.html_url)
    Write-Output ('Release ID: ' + $release.id)
} finally {
    $token = $null
    $passwordLine = $null
    $credentialLines = $null
    $headers = $null
    if ($null -eq $previousPrompt) {
        Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
    } else {
        $env:GIT_TERMINAL_PROMPT = $previousPrompt
    }
}
