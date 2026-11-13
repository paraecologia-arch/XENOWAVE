param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [ValidateSet("beta", "stable")][string]$Channel = "beta",
    [string]$ApplicationId = "com.dd3boh.xenowave",
    [string]$OutDir,
    [string]$DeltaPatchPath,
    [string]$DeltaFromApkPath,
    [int]$DeltaFromVersionCode = 0,
    [string]$DeltaFormat = "xnd-cdc",
    [int]$DeltaFormatVersion = 1,
    [int]$DeltaMinClientVersionCode = 0,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
if ([System.IO.Path]::GetExtension($apk) -ne ".apk") {
    throw "The input must be an .apk file."
}
if ($VersionCode -le 0) { throw "VersionCode must be positive." }
if ($VersionName -notmatch '^[0-9]{1,4}(\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.\-]{1,32})?$') {
    throw "VersionName must look like 0.10.2 or 0.10.2-beta.1."
}

$file = Get-Item -LiteralPath $apk
$size = $file.Length
if ($size -lt (1MB) -or $size -gt (250MB)) {
    throw "Unexpected APK size: $size bytes."
}

$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$apkName = $file.Name

$deltaEntry = $null
$deltaHash = $null
$deltaName = $null
if ($DeltaPatchPath -or $DeltaFromApkPath) {
    if (-not $DeltaPatchPath -or -not $DeltaFromApkPath -or $DeltaFromVersionCode -le 0) {
        throw "Delta generation requires -DeltaPatchPath, -DeltaFromApkPath and -DeltaFromVersionCode."
    }
    $patch = (Resolve-Path -LiteralPath $DeltaPatchPath).Path
    $baseApk = (Resolve-Path -LiteralPath $DeltaFromApkPath).Path
    $patchFile = Get-Item -LiteralPath $patch
    $deltaName = $patchFile.Name
    if ($deltaName -notmatch '^[A-Za-z0-9._-]{1,120}\.(xnd|patch)$') {
        throw "Unexpected delta patch name: $deltaName"
    }
    if ($patchFile.Length -lt 4KB -or $patchFile.Length -gt 100MB) {
        throw "Unexpected delta patch size: $($patchFile.Length) bytes."
    }
    $deltaHash = (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash.ToLowerInvariant()
    $baseHash = (Get-FileHash -LiteralPath $baseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $deltaEntry = [ordered]@{
        fromVersionCode       = $DeltaFromVersionCode
        fromApkSha256         = $baseHash
        patchName             = $deltaName
        patchSizeBytes        = $patchFile.Length
        patchSha256           = $deltaHash
        format                = $DeltaFormat
        formatVersion         = $DeltaFormatVersion
    }
    if ($DeltaMinClientVersionCode -gt 0) {
        $deltaEntry.minClientVersionCode = $DeltaMinClientVersionCode
    }
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = $file.Directory.FullName
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$updateJsonPath = Join-Path $OutDir "update.json"
if ((Test-Path -LiteralPath $updateJsonPath) -and -not $Force) {
    throw "update.json already exists in '$OutDir'. Use -Force only when replacing unpublished metadata."
}

$payload = [ordered]@{
    schemaVersion = 1
    applicationId = $ApplicationId
    versionCode   = $VersionCode
    versionName   = $VersionName
    channel       = $Channel
    minSdk        = 24
    apkName       = $apkName
    sizeBytes     = $size
    sha256        = $hash
}
if ($deltaEntry -ne $null) {
    $payload["deltas"] = @($deltaEntry)
}
$json = $payload | ConvertTo-Json -Depth 5
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($updateJsonPath, $json, $utf8)
$sums = @("$hash  $apkName")
if ($deltaEntry -ne $null) {
    $sums += "$deltaHash  $deltaName"
}
[System.IO.File]::WriteAllText(
    (Join-Path $OutDir "SHA256SUMS.txt"),
    ($sums -join "`n") + "`n",
    $utf8
)

Write-Output "APK: $apk"
Write-Output "SHA256: $hash"
Write-Output "Size: $size"
Write-Output "Metadata: $updateJsonPath"
