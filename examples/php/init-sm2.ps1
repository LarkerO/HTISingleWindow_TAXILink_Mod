param(
    [string]$OpenSsl = 'openssl',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'sm2-keys')
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "Key directory already exists: $OutputDirectory"
}
$root = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
foreach ($role in 'mod', 'gateway') {
    $dir = (New-Item -ItemType Directory -Path (Join-Path $root $role)).FullName
    $privateKey = Join-Path $dir "taxilink-$role-private.pem"
    $publicKey = Join-Path $dir "taxilink-$role-public.pem"
    & $OpenSsl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:SM2 -out $privateKey
    if ($LASTEXITCODE -ne 0) { throw "Could not generate $role SM2 key" }
    & $OpenSsl pkey -in $privateKey -pubout -out $publicKey
    if ($LASTEXITCODE -ne 0) { throw "Could not export $role SM2 public key" }
}
Copy-Item -LiteralPath (Join-Path $root 'mod/taxilink-mod-public.pem') -Destination (Join-Path $root 'gateway')
Copy-Item -LiteralPath (Join-Path $root 'gateway/taxilink-gateway-public.pem') -Destination (Join-Path $root 'mod')
Write-Output "SM2 keys generated under $root"
