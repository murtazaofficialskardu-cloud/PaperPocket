$ErrorActionPreference = 'Stop'
$version = '8.13'
$gradleCacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$cache = Join-Path $gradleCacheRoot 'paperpocket-distributions'
$destination = Join-Path $cache "gradle-$version"
if (Test-Path (Join-Path $destination 'bin\gradle.bat')) { exit 0 }
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$staging = Join-Path $cache ('.bootstrap-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    # Keep normal Windows certificate validation and configured proxy routing.
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $base = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
    Write-Host "Installing Gradle $version from services.gradle.org (network access required)."
    $checksumFile = Join-Path $staging 'checksum'
    $archive = Join-Path $staging 'gradle.zip'
    $requestOptions = @{}
    if ($env:HTTPS_PROXY) { $requestOptions.Proxy = $env:HTTPS_PROXY }
    elseif ($env:HTTP_PROXY) { $requestOptions.Proxy = $env:HTTP_PROXY }
    Invoke-WebRequest -UseBasicParsing @requestOptions -Uri "$base.sha256" -OutFile $checksumFile
    Invoke-WebRequest -UseBasicParsing @requestOptions -Uri $base -OutFile $archive
    $expected = (Get-Content -Raw $checksumFile).Trim().ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid official SHA-256 response.' }
    $actual = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw 'Gradle checksum mismatch; refusing installation.' }
    Expand-Archive -Path $archive -DestinationPath $staging
    if (-not (Test-Path (Join-Path $staging "gradle-$version\bin\gradle.bat"))) { throw 'Gradle archive is incomplete.' }
    Move-Item -Path (Join-Path $staging "gradle-$version") -Destination $destination
    Write-Host "Installed Gradle $version."
} finally {
    Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
}
