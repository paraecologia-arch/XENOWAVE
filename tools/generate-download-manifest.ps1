param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$ReleaseTag,
    [Parameter(Mandatory = $true)][string]$OutPath,
    [string]$PublishedAt = "",
    [string]$ApplicationId = "com.dd3boh.xenowave",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$apk = Get-Item -LiteralPath (Resolve-Path -LiteralPath $ApkPath).Path
if ($apk.Extension -ne ".apk") { throw "The input must be an .apk file." }
if ($VersionCode -le 0) { throw "VersionCode must be positive." }
if ($VersionName -notmatch '^[0-9]{1,4}(\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.\-]{1,32})?$') {
    throw "Unexpected version name: $VersionName"
}
if ($ReleaseTag -notmatch '^[0-9A-Za-z._-]{1,80}$') { throw "Unexpected release tag: $ReleaseTag" }
if ($apk.Name -notmatch '^[A-Za-z0-9._-]{1,120}\.apk$') { throw "Unexpected APK name." }
if (-not $apk.Name.EndsWith("-core.apk")) { throw "Only the core APK may be offered on the page." }
if ($apk.Length -lt 1MB -or $apk.Length -gt 250MB) { throw "Unexpected APK size." }

if ((Test-Path -LiteralPath $OutPath) -and -not $Force) {
    throw "Refusing to overwrite $OutPath without -Force."
}
if ([string]::IsNullOrWhiteSpace($PublishedAt)) {
    $PublishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
}

$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$url = "https://github.com/paraecologia-arch/XENOWAVE/releases/download/$ReleaseTag/$($apk.Name)"
$payload = [ordered]@{
    schemaVersion = 1
    applicationId = $ApplicationId
    channel       = "beta"
    versionName   = $VersionName
    versionCode   = $VersionCode
    minSdk        = 24
    apkName       = $apk.Name
    sizeBytes     = $apk.Length
    sha256        = $hash
    url           = $url
    publishedAt   = $PublishedAt
}

$parent = Split-Path -Parent $OutPath
if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$json = $payload | ConvertTo-Json -Depth 4
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($OutPath, $json, $utf8)

Write-Output "Manifest: $OutPath"
Write-Output "URL: $url"
Write-Output "SHA256: $hash"
Write-Output "Size: $($apk.Length)"
