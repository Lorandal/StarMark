param(
    [Parameter(Mandatory=$true)][string]$BuildConfig,
    [Parameter(Mandatory=$true)][string]$SourceHash,
    [Parameter(Mandatory=$true)][string]$Timestamp,
    [Parameter(Mandatory=$true)][string]$Channel,
    [Parameter(Mandatory=$true)][string]$VersionName
)
$ErrorActionPreference='Stop'
if($SourceHash -cnotmatch '^[0-9a-f]{64}$'){throw 'Delivery requires a valid source snapshot SHA-256'}
$taskText=Get-Content -LiteralPath $BuildConfig -Raw
$taskFields=@{
    SOURCE_SHA256=$SourceHash
    BUILD_TIMESTAMP=$Timestamp
    BUILD_CHANNEL=$Channel
    VERSION_NAME=$VersionName
}
foreach($taskField in $taskFields.GetEnumerator()){
    $taskDeclaration='public static final String '+$taskField.Key+' = "'+$taskField.Value+'";'
    if(-not $taskText.Contains($taskDeclaration)){
        throw "Compiled build identity mismatch: $($taskField.Key). APKs must not be delivered."
    }
}
Write-Output "Compiled build identity verified: $VersionName / $($SourceHash.Substring(0,7))"
