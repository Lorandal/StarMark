param([Parameter(ValueFromRemainingArguments=$true)][string[]]$FlutterArguments)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
if(-not $env:PUB_CACHE){$env:PUB_CACHE=Join-Path $taskRoot '.tooling/pub-cache'}
if(-not $env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME=Join-Path $taskRoot '.tooling/gradle'}
if(-not $env:JAVA_HOME){
    $taskJdkCandidates=@((Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'))
    $env:JAVA_HOME=$taskJdkCandidates | Where-Object {Test-Path -LiteralPath $_ -PathType Container} | Select-Object -First 1
    if(-not $env:JAVA_HOME){throw '请设置 JAVA_HOME，指向 Android Studio 的 jbr 或兼容的 JDK。'}
}
if(-not $env:ANDROID_HOME){
    $env:ANDROID_HOME=if($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
}
if($env:STARMARK_FLUTTER_HOME){
    $taskFlutter=Join-Path $env:STARMARK_FLUTTER_HOME 'bin/flutter.bat'
    if(-not (Test-Path -LiteralPath $taskFlutter)){throw 'STARMARK_FLUTTER_HOME 中未找到 bin/flutter.bat。'}
} elseif(Test-Path -LiteralPath (Join-Path $taskRoot '.tooling/flutter/bin/flutter.bat')){
    $taskFlutter=Join-Path $taskRoot '.tooling/flutter/bin/flutter.bat'
} else {
    $taskFlutter=(Get-Command flutter.bat -ErrorAction SilentlyContinue).Source
    if(-not $taskFlutter){throw '请设置 STARMARK_FLUTTER_HOME，或将 Flutter SDK 的 bin 目录加入 PATH。'}
}
if($FlutterArguments[0] -eq 'test' -and @($FlutterArguments | Where-Object {$_ -like '*integration_test*'}).Count -gt 0 -and $FlutterArguments -notcontains '--uninstall' -and $FlutterArguments -notcontains '--no-uninstall'){
    # Flutter treats options after a test path as additional test paths.
    $FlutterArguments = @($FlutterArguments[0], '--no-uninstall') + @($FlutterArguments | Select-Object -Skip 1)
}
& $taskFlutter @FlutterArguments
exit $LASTEXITCODE
