#Requires -Version 5.1
<#
.SYNOPSIS
  Builds the Hermes Loader APK.

.DESCRIPTION
  Locates a JDK 17 and the Android SDK (platform 34 + build-tools 34.0.0),
  writes local.properties when needed and runs the Gradle wrapper.

.PARAMETER Task
  Gradle task to run. Default: :app:assembleDebug

.PARAMETER NoClean
  Skip the implicit `clean`. Faster, but AGP's incremental packager then reuses
  the previous APK and can leave the old dex behind as dead padding.

.PARAMETER Publish
  After a successful build, commit and push the working tree, tag
  `v<versionName>` and create (or update) a GitHub release with the archived
  APK. Release notes come from the matching section of CHANGELOG.md.

.PARAMETER Jdk
  Path to a JDK 17 home. Overrides auto-detection and $env:JAVA_HOME.

.PARAMETER Sdk
  Path to the Android SDK. Overrides auto-detection and $env:ANDROID_HOME.

.EXAMPLE
  .\build.ps1
  .\build.ps1 -Task :app:assembleRelease
  .\build.ps1 -Task ':app:assembleDebug :app:lintDebug' -Publish
  .\build.ps1 -Sdk 'D:\android-sdk'
#>
[CmdletBinding()]
param(
    [string]$Task = ':app:assembleDebug',
    [switch]$NoClean,
    [switch]$Publish,
    [string]$Jdk,
    [string]$Sdk
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Find-Jdk {
    param([string]$Hint)

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Hint) { $candidates.Add($Hint) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    if ($env:JDK17) { $candidates.Add($env:JDK17) }
    $candidates.Add((Join-Path $PSScriptRoot 'tools\jdk17'))

    foreach ($root in @('C:\Program Files\Microsoft', 'C:\Program Files\Java',
                        'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Android\Android Studio\jbr')) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $java = Join-Path $candidate 'bin\java.exe'
        if (Test-Path -LiteralPath $java) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return $null
}

function Find-Sdk {
    param([string]$Hint)

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Hint) { $candidates.Add($Hint) }
    if ($env:ANDROID_HOME) { $candidates.Add($env:ANDROID_HOME) }
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add($env:ANDROID_SDK_ROOT) }
    $candidates.Add((Join-Path $PSScriptRoot 'tools\sdk'))
    $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $marker = Join-Path $candidate 'platforms'
        if (Test-Path -LiteralPath $marker) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return $null
}

$jdkHome = Find-Jdk -Hint $Jdk
if (-not $jdkHome) {
    throw 'JDK 17 not found. Install it or pass -Jdk <path>.'
}
$sdkHome = Find-Sdk -Hint $Sdk
if (-not $sdkHome) {
    throw 'Android SDK not found. Install it or pass -Sdk <path>.'
}

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkHome
$env:ANDROID_SDK_ROOT = $sdkHome
$env:PATH = "$jdkHome\bin;$sdkHome\platform-tools;$env:PATH"

Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"

$localProps = Join-Path $PSScriptRoot 'local.properties'
$sdkLine = "sdk.dir=$($sdkHome -replace '\\', '\\')"
$needsWrite = $true
if (Test-Path -LiteralPath $localProps) {
    $current = Get-Content -LiteralPath $localProps -Raw
    if ($current -match '(?m)^sdk\.dir=') {
        $current = [regex]::Replace($current, '(?m)^sdk\.dir=.*$', $sdkLine)
        Set-Content -LiteralPath $localProps -Value $current -NoNewline
        $needsWrite = $false
    }
}
if ($needsWrite) {
    Set-Content -LiteralPath $localProps -Value $sdkLine
}

# AGP's packager reuses the APK from the previous run and moves changed dex
# files to the end, leaving the old copy in place as dead padding (~65 KB per
# change). Two builds of the same sources then produce different bytes, which
# defeats the archive in releases\. A clean build writes the zip from scratch.
$gradleTasks = @($Task -split '\s+' | Where-Object { $_ })
if (-not $NoClean -and $gradleTasks -notcontains 'clean') {
    $gradleTasks = @('clean') + $gradleTasks
}
& (Join-Path $PSScriptRoot 'gradlew.bat') @gradleTasks --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE."
}

$apk = $null
$variant = if ($Task -match 'Release') { 'release' } else { 'debug' }
$apkDir = Join-Path $PSScriptRoot "app\build\outputs\apk\$variant"
if (Test-Path -LiteralPath $apkDir) {
    $apk = Get-ChildItem -LiteralPath $apkDir -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}
if (-not $apk) {
    $apk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk') -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

if ($apk) {
    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host ''
    Write-Host "APK:  $($apk.FullName)"
    Write-Host "Size: $sizeMb MB"
    if ($variant -eq 'debug') {
        $webApk = Join-Path $PSScriptRoot 'app-debug-web.apk'
        if (Test-Path -LiteralPath $webApk) {
            Write-Host "Dashboard build (same APK): $webApk"
        }
    }

    # The versioned copy is what survives the next build; the two files above
    # are just "latest" pointers. Prefer the file with the same bytes.
    $releaseDir = Join-Path $PSScriptRoot 'releases'
    $apkHash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLower()
    $archived = $null
    if (Test-Path -LiteralPath $releaseDir) {
        $archived = Get-ChildItem -LiteralPath $releaseDir -Filter '*.apk' -File -ErrorAction SilentlyContinue |
            Where-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower() -eq $apkHash } |
            Select-Object -First 1
    }
    if ($archived) {
        Write-Host "Archive: $($archived.Name)  (releases\, immutable)"
    } else {
        Write-Host "Archive: not written (unsigned or archive disabled) - see releases\README.md"
    }
    Write-Host "SHA-256: $apkHash"

    Write-Host ''
    if ($apk.Name -match '-unsigned') {
        Write-Host 'This APK is UNSIGNED. Sign it first, for example:'
        Write-Host "  & `"$sdkHome\build-tools\34.0.0\apksigner.bat`" sign --ks <keystore> --out signed.apk `"$($apk.FullName)`""
    } else {
        Write-Host 'Install with:'
        Write-Host "  & `"$sdkHome\platform-tools\adb.exe`" install -r `"$($apk.FullName)`""
        if ($archived -and $archived.FullName -ne $apk.FullName) {
            Write-Host 'Roll back with (keeps storage/):'
            Write-Host "  & `"$sdkHome\platform-tools\adb.exe`" install -r -d `"$($archived.FullName)`""
        }
    }
}

if ($Publish) {
    if (-not $apk) {
        throw 'Publish: the build produced no APK.'
    }
    if (-not $archived) {
        throw 'Publish: the APK is not archived (unsigned or archiving disabled).'
    }

    $gradleFile = Join-Path $PSScriptRoot 'app\build.gradle.kts'
    $gradleText = Get-Content -LiteralPath $gradleFile -Raw
    $versionName = [regex]::Match($gradleText, '(?m)^\s*versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [regex]::Match($gradleText, '(?m)^\s*versionCode\s*=\s*(\d+)').Groups[1].Value
    if (-not $versionName -or -not $versionCode) {
        throw 'Publish: cannot read versionName/versionCode from app/build.gradle.kts.'
    }
    foreach ($tool in @('git', 'gh')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Publish: '$tool' is not installed or not on PATH."
        }
    }

    & git -C $PSScriptRoot rev-parse --is-inside-work-tree | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Publish: the project is not a git repository.'
    }
    $remote = (& git -C $PSScriptRoot remote get-url origin 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $remote) {
        throw 'Publish: no "origin" remote is configured.'
    }

    Write-Host ''
    Write-Host "Publishing $versionName (versionCode $versionCode) to $remote ..."

    & git -C $PSScriptRoot add -A
    if ($LASTEXITCODE -ne 0) { throw 'Publish: git add failed.' }
    $dirty = (& git -C $PSScriptRoot status --porcelain)
    if ($dirty) {
        & git -C $PSScriptRoot commit -m "Release $versionName"
        if ($LASTEXITCODE -ne 0) { throw 'Publish: git commit failed.' }
        Write-Host "Committed working tree as 'Release $versionName'."
    } else {
        Write-Host 'Working tree already clean.'
    }

    & git -C $PSScriptRoot push origin HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Publish: git push failed.' }

    $tag = "v$versionName"
    $remoteTag = (& git -C $PSScriptRoot ls-remote --tags origin "refs/tags/$tag" 2>$null)
    if (-not $remoteTag) {
        $localTag = (& git -C $PSScriptRoot tag -l $tag 2>$null)
        if (-not $localTag) {
            & git -C $PSScriptRoot tag -a $tag -m "Hermes Loader $versionName"
            if ($LASTEXITCODE -ne 0) { throw "Publish: could not create tag $tag." }
        }
        & git -C $PSScriptRoot push origin "refs/tags/$tag"
        if ($LASTEXITCODE -ne 0) { throw "Publish: could not push tag $tag." }
        Write-Host "Pushed tag $tag."
    }

    # Release notes: the CHANGELOG section for this version plus APK facts.
    $notes = "## Hermes Loader $versionName`n`n"
    $changelog = Join-Path $PSScriptRoot 'CHANGELOG.md'
    if (Test-Path -LiteralPath $changelog) {
        $changelogText = Get-Content -LiteralPath $changelog -Raw
        $pattern = "(?ms)^##\s+" + [regex]::Escape($versionName) + "\s.*?(?=^##\s+|\z)"
        $section = [regex]::Match($changelogText, $pattern).Value
        if ($section) {
            $notes = $section.Trim() + "`n`n"
        }
    }
    $sizeMb = [math]::Round($archived.Length / 1MB, 2)
    $notes += "**APK:** ``$($archived.Name)`` ($($archived.Length) bytes, $sizeMb MiB)`n`n"
    $notes += "**SHA-256:** ``$apkHash``"
    $report = Join-Path $PSScriptRoot "REPORT-Hermes-Loader-$versionName.md"
    if (Test-Path -LiteralPath $report) {
        $notes += "`n`nПовний звіт: ``REPORT-Hermes-Loader-$versionName.md``"
    }

    $notesPath = Join-Path $env:TEMP "hermes-loader-$versionName-notes.md"
    [System.IO.File]::WriteAllText($notesPath, $notes + "`n",
        (New-Object System.Text.UTF8Encoding($false)))

    & gh release view $tag --json name 2>$null | Out-Null
    $releaseExists = ($LASTEXITCODE -eq 0)
    try {
        if ($releaseExists) {
            & gh release upload $tag $archived.FullName --clobber
            if ($LASTEXITCODE -ne 0) { throw "Publish: asset upload failed for $tag." }
            & gh release edit $tag --title "Hermes Loader $versionName" --notes-file $notesPath
            if ($LASTEXITCODE -ne 0) { throw "Publish: could not update release $tag." }
            Write-Host "Updated GitHub release $tag."
        } else {
            & gh release create $tag $archived.FullName `
                --title "Hermes Loader $versionName" --notes-file $notesPath --latest
            if ($LASTEXITCODE -ne 0) { throw "Publish: could not create release $tag." }
            Write-Host "Created GitHub release $tag."
        }
    } finally {
        Remove-Item -LiteralPath $notesPath -ErrorAction SilentlyContinue
    }
}
