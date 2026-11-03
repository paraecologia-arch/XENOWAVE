param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [ValidateSet("beta", "stable")][string]$Channel = "beta",
    [string]$ApplicationId = "com.dd3boh.xenowave",
    [string]$OutDir,
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
$json = $payload | ConvertTo-Json -Depth 3
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($updateJsonPath, $json, $utf8)
[System.IO.File]::WriteAllText(
    (Join-Path $OutDir "SHA256SUMS.txt"),
    "$hash  $apkName`n",
    $utf8
)

Write-Output "APK: $apk"
Write-Output "SHA256: $hash"
Write-Output "Size: $size"
Write-Output "Metadata: $updateJsonPath"
