param([int]$Port = 5173, [switch]$Watch, [switch]$NoSync)

$ErrorActionPreference = 'Stop'
$bundledNodePath = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
if (Test-Path -LiteralPath $bundledNodePath -PathType Leaf) {
    $nodePath = $bundledNodePath
} else {
    $nodePath = (Get-Command node -ErrorAction Stop).Source
}
$scriptArguments = @((Join-Path $PSScriptRoot 'serve-web.mjs'), '--port', "$Port")
if ($Watch) { $scriptArguments += '--watch' }
if ($NoSync) { $scriptArguments += '--no-sync' }
& $nodePath @scriptArguments
exit $LASTEXITCODE
