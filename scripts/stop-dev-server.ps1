<#
.SYNOPSIS
    Stops the Hytale dev server that `./gradlew runServer` leaves behind on Windows.

.DESCRIPTION
    Stopping the Gradle task does not stop the server: Gradle's process tree is
    severed and the server JVM keeps running, holding `run/` open so the next
    boot fights it for the data directory.

    This script matches ONLY a java.exe whose command line contains THIS
    repository's own build output path, so it cannot touch another Java
    process - not another Hytale server, not a build, not an IDE. That narrow
    match is the point: it is what makes the script safe to allowlist in
    .claude/settings.local.json, where a bare `taskkill` would not be.

    Exits 0 when there was nothing to stop, so it is safe to run before a boot.
#>
[CmdletBinding()]
param(
    # Seconds to wait for each process to go away before reporting it stuck.
    [int] $TimeoutSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# The classpath entry runServer puts on the JVM command line. Matching the repo
# root alone would also catch a Gradle daemon working on this project.
$marker = Join-Path $repoRoot 'build\classes\java\main'

$targets = @(
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($marker) }
)

if ($targets.Count -eq 0) {
    Write-Output 'No MysticEssentials dev-server JVM is running.'
    exit 0
}

foreach ($proc in $targets) {
    Write-Output "Stopping dev-server JVM PID $($proc.ProcessId)"
    try {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    } catch {
        Write-Output "  could not stop PID $($proc.ProcessId): $($_.Exception.Message)"
    }
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 300
    $left = @(
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($marker) }
    )
} while ($left.Count -gt 0 -and (Get-Date) -lt $deadline)

if ($left.Count -gt 0) {
    Write-Output "Still running after ${TimeoutSeconds}s: $($left.ProcessId -join ', ')"
    exit 1
}

Write-Output 'Dev-server JVM stopped.'
exit 0
