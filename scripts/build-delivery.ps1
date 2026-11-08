param([string]$Python, [ValidateSet("release", "debug")][string]$Mode = "release")
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
if(-not $Python){
    $taskBundledPython=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    $Python=if(Test-Path -LiteralPath $taskBundledPython){$taskBundledPython}else{'python'}
}
Push-Location $taskRoot
try {
    & (Join-Path $PSScriptRoot 'flutter.ps1') pub get --offline
    if($LASTEXITCODE -ne 0){throw 'Dependency preparation failed'}
    $taskPackTime=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time')
    $taskPackDate=$taskPackTime.ToString('yyyyMMdd')
    $taskVersionJson=& $Python (Join-Path $PSScriptRoot 'version_channels.py') --snapshot --date $taskPackDate
    if($LASTEXITCODE -ne 0){throw 'Source snapshot failed'}
    $taskVersionInfo=$taskVersionJson | ConvertFrom-Json
    $taskVersion=$taskVersionInfo.versionName
    $taskTimestamp=$taskPackTime.ToString('yyyy/MM/dd HH:mm:ss')
    # Pass identity as explicit Gradle project arguments through Flutter.
    # Environment-only injection can silently leave SOURCE_SHA256 empty.
    $taskSourceArgument="--android-project-arg=starmarkSourceSha256=$($taskVersionInfo.sourceSha256)"
    $taskTimeArgument="--android-project-arg=starmarkBuildTimestamp=$taskTimestamp"
    # Studio/run APKs include only the connected device's Flutter engine. Build
    # all requested engines explicitly; never publish its shared app-debug.apk.
    # Flutter regenerates the plugin registrant for the selected build mode
    # only when pub is enabled. A prior integration test can leave dev plugins
    # in the registrant, which are unavailable to release builds.
    & (Join-Path $PSScriptRoot 'flutter.ps1') build apk "--$Mode" --target=lib/main.dart '--target-platform=android-arm,android-arm64,android-x64' --split-per-abi $taskSourceArgument $taskTimeArgument
    if($LASTEXITCODE -ne 0){throw 'Delivery build failed'}
    $taskBuildConfig=Join-Path $taskRoot "build/app/generated/source/buildConfig/$Mode/dev/starmark/starmark/BuildConfig.java"
    & (Join-Path $PSScriptRoot 'verify-build-identity.ps1') -BuildConfig $taskBuildConfig -SourceHash $taskVersionInfo.sourceSha256 -Timestamp $taskTimestamp -Channel $taskVersionInfo.channel -VersionName $taskVersion
    $taskFinalSnapshotJson=& $Python (Join-Path $PSScriptRoot 'version_channels.py') --snapshot --date $taskPackDate
    if($LASTEXITCODE -ne 0){throw 'Post-build source verification failed'}
    $taskFinalSnapshot=$taskFinalSnapshotJson | ConvertFrom-Json
    if($taskFinalSnapshot.sourceSha256 -ne $taskVersionInfo.sourceSha256){throw 'Source changed during build; APKs were not delivered. Rebuild the frozen source.'}
    $taskReports=@()
    foreach($taskAbi in @('armeabi-v7a','arm64-v8a','x86_64')){
        $taskSource=Join-Path $taskRoot "build/app/outputs/flutter-apk/app-$taskAbi-$Mode.apk"
        $taskValidation=& $Python (Join-Path $PSScriptRoot 'check_android_apk.py') $taskSource --abis $taskAbi
        if($LASTEXITCODE -ne 0){throw "Engine validation failed: $taskAbi"}
        $taskReports+=($taskValidation | ConvertFrom-Json)
        $taskAapt=Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/aapt.exe'
        $taskManifest=(& $taskAapt dump badging $taskSource | Select-Object -First 1)
        if($LASTEXITCODE -ne 0 -or -not $taskManifest.Contains("name='$($taskVersionInfo.applicationId)'") -or -not $taskManifest.Contains("versionName='$taskVersion'")){throw "APK channel/package/version mismatch: $taskAbi"}
        $taskSigner=Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
        $taskSigning=& $taskSigner verify --verbose --print-certs --min-sdk-version 21 $taskSource 2>&1
        if($LASTEXITCODE -ne 0){throw "Signature validation failed: $taskAbi"}
        foreach($taskScheme in @('v1','v2','v3')){
            if(($taskSigning -join "`n") -notmatch "Verified using $taskScheme scheme.*true"){throw "Missing $taskScheme signature: $taskAbi"}
        }
        $taskReports[-1] | Add-Member -NotePropertyName signing -NotePropertyValue ($taskSigning -join "`n")
    }
    $taskArtifacts=Join-Path $taskRoot 'artifacts'
    New-Item -ItemType Directory -Path $taskArtifacts -Force | Out-Null
    # Copy only after every architecture passes, so partial builds are not delivered.
    foreach($taskReport in $taskReports){
        $taskAbi=$taskReport.abis[0]
        $taskModeSuffix=if($Mode -eq 'debug'){'-debug'}else{''}
        $taskName="StarMark-$taskVersion-$taskAbi$taskModeSuffix.apk"
        Copy-Item -LiteralPath (Join-Path $taskRoot "build/app/outputs/flutter-apk/$($taskReport.file)") -Destination (Join-Path $taskArtifacts $taskName) -Force
        Set-Content -LiteralPath (Join-Path $taskArtifacts "$taskName.sha256") -Value "$($taskReport.sha256)  $taskName" -Encoding utf8
        $taskReport.file=$taskName
    }
    $taskReports | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskArtifacts "StarMark-$taskVersion-$Mode-apk-report.json") -Encoding utf8
    $taskVersionInfo | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskArtifacts "StarMark-$taskVersion-source.json") -Encoding utf8
    Write-Output "Phone APK: $(Join-Path $taskArtifacts "StarMark-$taskVersion-arm64-v8a$taskModeSuffix.apk")"
} finally {
    Pop-Location
}
