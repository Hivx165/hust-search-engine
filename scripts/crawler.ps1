param(
    [ValidateSet('start', 'status', 'stop', 'enable-autostart', 'disable-autostart', 'foreground')]
    [string]$Action = 'status',
    [string]$StoragePath = 'storage',
    [string]$JavaPath = '',
    [ValidateRange(1, 1440)][int]$IntervalMinutes = 5,
    [ValidateRange(1, 10080)][int]$RevisitMinutes = 60,
    [ValidateRange(1, 120)][int]$RoundMinutes = 15,
    [ValidateRange(1, 1000000)][int]$MaxDocuments = 1000,
    [ValidateRange(1, 1000000)][int]$MaxVisits = 10000,
    [ValidateRange(1000, 60000)][int]$DelayMs = 1000
)
$ErrorActionPreference = 'Stop'
$workspacePath = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$scriptPath = Join-Path $PSScriptRoot 'crawler.ps1'
$jarPath = Join-Path $workspacePath 'crawler-module\target\crawler-module-1.0-SNAPSHOT.jar'
if ([IO.Path]::IsPathRooted($StoragePath)) { $crawlerStorage = [IO.Path]::GetFullPath($StoragePath) }
else { $crawlerStorage = [IO.Path]::GetFullPath((Join-Path $workspacePath $StoragePath)) }
$pidPath = Join-Path $crawlerStorage 'crawler.pid'
$sha = [Security.Cryptography.SHA256]::Create()
try { $suffix = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($crawlerStorage.ToLowerInvariant()))).Replace('-', '').Substring(0, 10) }
finally { $sha.Dispose() }
$taskName = 'HustSearchCrawler-' + $suffix

function Quote-Argument([string]$Value) {
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '\\+$', '$0$0')
    return '"' + $escaped + '"'
}
function Test-ManagedCommandLine([string]$CommandLine, [string]$ProcessName) {
    if ($ProcessName -ine 'java.exe' -or -not $CommandLine) { return $false }
    $jarArgument = (Quote-Argument '-jar') + ' ' + (Quote-Argument $jarPath)
    $storageArgument = (Quote-Argument '--storage') + ' ' + (Quote-Argument $crawlerStorage)
    return $CommandLine.Contains($jarArgument) -and $CommandLine.Contains($storageArgument)
}

function Find-Crawler {
    if (-not (Test-Path -LiteralPath $pidPath)) { return $null }
    $crawlerProcessId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $pidPath -Raw).Trim(), [ref]$crawlerProcessId)) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $crawlerProcessId" -ErrorAction SilentlyContinue
    if ($process -and (Test-ManagedCommandLine $process.CommandLine $process.Name)) {
        return $process
    }
    return $null
}
function Show-Status {
    $process = Find-Crawler
    if ($process) { Write-Output "RUNNING: PID $($process.ProcessId)" }
    else { Write-Output 'STOPPED: no managed crawler process found.' }
    Write-Output "Storage: $crawlerStorage"
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($task) { Write-Output "Autostart: $taskName ($($task.State))" }
    else { Write-Output 'Autostart: disabled' }
    foreach ($name in @('lifecycle.properties', 'crawler_status.properties', 'dataset_export_status.properties')) {
        $path = Join-Path $crawlerStorage $name
        if (Test-Path -LiteralPath $path) {
            $snapshot = @(Get-Content -LiteralPath $path | Where-Object { -not $_.StartsWith('#') })
            if ($name -eq 'crawler_status.properties' -and (-not $process -or $snapshot -notcontains ('process_id=' + $process.ProcessId))) {
                Write-Output 'Previous crawl snapshot:'
            }
            $snapshot
        }
    }
}
function Wait-CrawlerStart {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if (Find-Crawler) { return }
        Start-Sleep -Milliseconds 500
    }
}
function Get-Java {
    $candidates = @()
    if ($JavaPath) { $candidates += $JavaPath }
    else {
        if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
        $candidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\java.exe')
        $command = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
        if ($command) { $candidates += $command.Source }
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $candidate
        $info.Arguments = '-version'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardError = $true
        $info.RedirectStandardOutput = $true
        $probe = New-Object Diagnostics.Process
        $probe.StartInfo = $info
        try {
            [void]$probe.Start()
            $version = $probe.StandardError.ReadToEnd() + $probe.StandardOutput.ReadToEnd()
            $probe.WaitForExit()
            if ($version -match '(?:openjdk|java) version "([0-9]+)(?:\.([0-9]+))?') {
                $major = [int]$Matches[1]
                if ($major -eq 1) { $major = [int]$Matches[2] }
                if ($major -ge 17) { return $candidate }
            }
        } finally { $probe.Dispose() }
    }
    throw 'JDK 17+ is required. Pass -JavaPath C:\path\to\bin\java.exe.'
}
function Start-Java([bool]$Wait) {
    if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the crawler JAR with Maven verify first.' }
    $javaExecutable = Get-Java
    $logDirectory = Join-Path $crawlerStorage 'logs'
    [void](New-Item -ItemType Directory -Force -Path $logDirectory)
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
    $arguments = @('-jar', $jarPath, '--storage', $crawlerStorage,
        '--seeds', (Join-Path $workspacePath 'crawler-module\src\main\resources\initial_subdomains.txt'),
        '--subdomains-output', (Join-Path $workspacePath 'crawler-module\src\main\resources\initial_subdomains.txt'),
        '--discovery-mode', 'hybrid', '--interval-minutes', "$IntervalMinutes",
        '--revisit-minutes', "$RevisitMinutes", '--round-minutes', "$RoundMinutes",
        '--max-documents', "$MaxDocuments", '--max-visits', "$MaxVisits", '--delay-ms', "$DelayMs")
    $argumentText = ($arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    $process = Start-Process -FilePath $javaExecutable -ArgumentList $argumentText -WorkingDirectory $workspacePath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory "$stamp.out.log") -RedirectStandardError (Join-Path $logDirectory "$stamp.err.log")
    if ($Wait) { $process.WaitForExit(); return $process.ExitCode }
    Start-Sleep -Seconds 2
    if ($process.HasExited) { throw "Crawler exited with code $($process.ExitCode). Check $logDirectory." }
    Write-Output "Started PID $($process.Id). Logs: $logDirectory"
}

switch ($Action) {
    'status' { Show-Status }
    'start' {
        if (Find-Crawler) { Write-Output 'Crawler is already running.' }
        elseif (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
            Start-ScheduledTask -TaskName $taskName
            Wait-CrawlerStart
        } else { Start-Java $false }
        Show-Status
    }
    'foreground' {
        $existing = Find-Crawler
        if ($existing) { Wait-Process -Id $existing.ProcessId -ErrorAction SilentlyContinue; exit 1 }
        $exitCode = Start-Java $true
        if ($exitCode -eq 0) { exit 1 }
        exit $exitCode
    }
    'stop' {
        $process = Find-Crawler
        $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($task -and $task.State -eq 'Running') { Stop-ScheduledTask -TaskName $taskName }
        if ($process) { Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue }
        Write-Output 'Crawler stopped. Autostart remains configured unless disabled separately.'
    }
    'enable-autostart' {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $taskArguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $scriptPath + '" -Action foreground -StoragePath "' + $crawlerStorage + '" -IntervalMinutes ' + $IntervalMinutes + ' -RevisitMinutes ' + $RevisitMinutes + ' -RoundMinutes ' + $RoundMinutes + ' -MaxDocuments ' + $MaxDocuments + ' -MaxVisits ' + $MaxVisits + ' -DelayMs ' + $DelayMs
        if ($JavaPath) { $taskArguments += ' -JavaPath "' + $JavaPath + '"' }
        $taskAction = New-ScheduledTaskAction -Execute $powershell -Argument $taskArguments -WorkingDirectory $workspacePath
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
        $principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 2) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Description "Continuous HUST crawler for $workspacePath" -Force | Out-Null
        Start-ScheduledTask -TaskName $taskName
        Write-Output "Enabled autostart and failure recovery: $taskName"
        Wait-CrawlerStart
        Show-Status
    }
    'disable-autostart' {
        $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($task) {
            Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        }
        Write-Output 'Autostart disabled.'
    }
}
