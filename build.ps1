[CmdletBinding()]
param(
    [switch] $AcceptAndroidSdkLicense
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = $PSScriptRoot
$DependencyRoot = Join-Path $ProjectRoot '.build-deps'
$DownloadRoot = Join-Path $DependencyRoot 'downloads'
$BuildRoot = Join-Path $ProjectRoot '.build'
$OutputRoot = Join-Path $ProjectRoot 'out'

$BuildToolsUrl = 'https://dl.google.com/android/repository/build-tools_r35.0.1_windows.zip'
$BuildToolsSha256 = '79748cb4ab64b61fa678af21639985c7e394d874a4e31f082f4026d5c57e01a3'
$PlatformUrl = 'https://dl.google.com/android/repository/platform-31_r01.zip'
$PlatformSha256 = '1d69fe1d7f9788d82ff3a374faf4f6ccc9d1d372aa84a86b5bcfb517523b0b3f'

function Assert-LastExitCode {
    param([string] $Operation)
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

function Reset-ProjectDirectory {
    param([string] $Path)

    $rootFull = [System.IO.Path]::GetFullPath($ProjectRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    $requiredPrefix = $rootFull + [System.IO.Path]::DirectorySeparatorChar

    if (-not $pathFull.StartsWith($requiredPrefix,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to reset a directory outside the project: $pathFull"
    }

    if (Test-Path -LiteralPath $pathFull) {
        Remove-Item -LiteralPath $pathFull -Recurse -Force
    }
    New-Item -ItemType Directory -Path $pathFull -Force | Out-Null
}

function Get-VerifiedArchive {
    param(
        [string] $Url,
        [string] $Destination,
        [string] $ExpectedSha256
    )

    if (Test-Path -LiteralPath $Destination) {
        $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
        if ($actual.Equals($ExpectedSha256,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            return
        }
        Remove-Item -LiteralPath $Destination -Force
    }

    if (-not $AcceptAndroidSdkLicense) {
        throw @"
Android SDK components are not cached yet.
Read the Android SDK License, then rerun:
    .\build.ps1 -AcceptAndroidSdkLicense
"@
    }

    Write-Host "Downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing

    $downloadedSha256 = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
    if (-not $downloadedSha256.Equals($ExpectedSha256,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $Destination -Force
        throw "Checksum mismatch for $Url"
    }
}

if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) {
    throw 'This build script currently supports Windows only.'
}

$Java = (Get-Command 'java.exe' -ErrorAction Stop).Source
$Javac = (Get-Command 'javac.exe' -ErrorAction Stop).Source
$Jar = (Get-Command 'jar.exe' -ErrorAction Stop).Source
$Keytool = (Get-Command 'keytool.exe' -ErrorAction Stop).Source

New-Item -ItemType Directory -Path $DownloadRoot, $OutputRoot -Force | Out-Null

$BuildToolsArchive = Join-Path $DownloadRoot 'build-tools_r35.0.1_windows.zip'
$PlatformArchive = Join-Path $DownloadRoot 'platform-31_r01.zip'
Get-VerifiedArchive -Url $BuildToolsUrl -Destination $BuildToolsArchive `
    -ExpectedSha256 $BuildToolsSha256
Get-VerifiedArchive -Url $PlatformUrl -Destination $PlatformArchive `
    -ExpectedSha256 $PlatformSha256

$BuildToolsExtract = Join-Path $DependencyRoot 'build-tools-35.0.1'
$AaptItem = Get-ChildItem -LiteralPath $BuildToolsExtract -Filter 'aapt.exe' `
    -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $AaptItem) {
    Reset-ProjectDirectory $BuildToolsExtract
    Expand-Archive -LiteralPath $BuildToolsArchive -DestinationPath $BuildToolsExtract
    $AaptItem = Get-ChildItem -LiteralPath $BuildToolsExtract -Filter 'aapt.exe' `
        -Recurse -File | Select-Object -First 1
}

$PlatformExtract = Join-Path $DependencyRoot 'platform-31'
$AndroidJarItem = Get-ChildItem -LiteralPath $PlatformExtract -Filter 'android.jar' `
    -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $AndroidJarItem) {
    Reset-ProjectDirectory $PlatformExtract
    Expand-Archive -LiteralPath $PlatformArchive -DestinationPath $PlatformExtract
    $AndroidJarItem = Get-ChildItem -LiteralPath $PlatformExtract -Filter 'android.jar' `
        -Recurse -File | Select-Object -First 1
}

if ($null -eq $AaptItem -or $null -eq $AndroidJarItem) {
    throw 'Downloaded Android SDK archives did not contain the expected tools.'
}

$BuildToolsHome = $AaptItem.Directory.FullName
$Aapt = $AaptItem.FullName
$Zipalign = Join-Path $BuildToolsHome 'zipalign.exe'
$Apksigner = Join-Path $BuildToolsHome 'apksigner.bat'
$D8Jar = Join-Path $BuildToolsHome 'lib\d8.jar'
$AndroidJar = $AndroidJarItem.FullName

foreach ($requiredFile in @($Zipalign, $Apksigner, $D8Jar, $AndroidJar)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Missing required build dependency: $requiredFile"
    }
}

Reset-ProjectDirectory $BuildRoot
$ClassRoot = Join-Path $BuildRoot 'classes'
$DexRoot = Join-Path $BuildRoot 'dex'
New-Item -ItemType Directory -Path $ClassRoot, $DexRoot -Force | Out-Null

$SourceRoot = Join-Path $ProjectRoot 'app\src'
$Manifest = Join-Path $ProjectRoot 'app\AndroidManifest.xml'
$Sources = Get-ChildItem -LiteralPath $SourceRoot -Recurse -Filter '*.java' -File |
    ForEach-Object FullName
if ($Sources.Count -eq 0) {
    throw "No Java source files found under $SourceRoot"
}

Write-Host 'Compiling Java sources...'
& $Javac -source 8 -target 8 -encoding UTF-8 -classpath $AndroidJar `
    -d $ClassRoot $Sources
Assert-LastExitCode 'Java compilation'

$ClassJar = Join-Path $BuildRoot 'matepad-audio-classes.jar'
& $Jar --create --file $ClassJar -C $ClassRoot .
Assert-LastExitCode 'Class archive creation'

Write-Host 'Converting classes to DEX...'
& $Java -cp $D8Jar com.android.tools.r8.D8 --min-api 28 --lib $AndroidJar `
    --output $DexRoot $ClassJar
Assert-LastExitCode 'DEX compilation'

$UnsignedBase = Join-Path $BuildRoot 'unsigned-base.apk'
$UnsignedApk = Join-Path $BuildRoot 'unsigned.apk'
$AlignedApk = Join-Path $BuildRoot 'aligned.apk'
$SignedApk = Join-Path $BuildRoot 'MatePadAudioLoopback-debug.apk'

Write-Host 'Packaging APK...'
& $Aapt package -f -M $Manifest -I $AndroidJar -F $UnsignedBase
Assert-LastExitCode 'Manifest packaging'
Copy-Item -LiteralPath $UnsignedBase -Destination $UnsignedApk -Force

Push-Location $DexRoot
try {
    & $Aapt add $UnsignedApk 'classes.dex'
    Assert-LastExitCode 'DEX packaging'
} finally {
    Pop-Location
}

& $Zipalign -f 4 $UnsignedApk $AlignedApk
Assert-LastExitCode 'APK alignment'

$SigningRoot = Join-Path $DependencyRoot 'signing'
New-Item -ItemType Directory -Path $SigningRoot -Force | Out-Null
$DebugKeystore = Join-Path $SigningRoot 'debug.keystore'
if (-not (Test-Path -LiteralPath $DebugKeystore)) {
    Write-Host 'Creating a local debug signing key...'
    & $Keytool -genkeypair -noprompt -keystore $DebugKeystore `
        -storepass android -keypass android -alias androiddebugkey `
        -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 `
        -validity 10000
    Assert-LastExitCode 'Debug key creation'
}

Write-Host 'Signing APK...'
& $Apksigner sign --ks $DebugKeystore --ks-key-alias androiddebugkey `
    --ks-pass 'pass:android' --key-pass 'pass:android' `
    --v4-signing-enabled false --out $SignedApk $AlignedApk
Assert-LastExitCode 'APK signing'
& $Apksigner verify --verbose $SignedApk
Assert-LastExitCode 'APK verification'

$FinalApk = Join-Path $OutputRoot 'MatePadAudioLoopback-debug.apk'
Copy-Item -LiteralPath $SignedApk -Destination $FinalApk -Force
Write-Host "Build complete: $FinalApk"
