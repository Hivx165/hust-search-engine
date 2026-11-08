param([string]$StoragePath = '')
$ErrorActionPreference = 'Stop'
if (-not $StoragePath) { $StoragePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'storage' }
$storageRoot = [IO.Path]::GetFullPath($StoragePath)
$compressed = Join-Path $storageRoot 'dataset.db.gz'
$expectedPath = Join-Path $storageRoot 'dataset.db.sha256'
$destination = Join-Path $storageRoot 'dataset.db'
if (Test-Path -LiteralPath $destination) {
    Write-Output 'dataset.db already exists; kept the current cache.'
    exit 0
}
if (-not (Test-Path -LiteralPath $compressed) -or -not (Test-Path -LiteralPath $expectedPath)) {
    throw 'The compressed dataset cache or its SHA-256 checksum is missing.'
}
$expected = (Get-Content -LiteralPath $expectedPath -Raw).Trim().Split(' ')[0]
if ($expected -notmatch '^[0-9a-fA-F]{64}$') { throw 'Invalid cache checksum.' }
$temporary = Join-Path $storageRoot ('restore-cache-' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    $input = [IO.File]::OpenRead($compressed)
    try {
        $gzip = New-Object IO.Compression.GZipStream($input, [IO.Compression.CompressionMode]::Decompress)
        try {
            $output = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew)
            try { $gzip.CopyTo($output) } finally { $output.Dispose() }
        } finally { $gzip.Dispose() }
    } finally { $input.Dispose() }
    $actual = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash
    if ($actual -ine $expected) { throw 'Cache checksum verification failed.' }
    [IO.File]::Move($temporary, $destination)
    Write-Output ('Restored and verified: ' + $destination)
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
