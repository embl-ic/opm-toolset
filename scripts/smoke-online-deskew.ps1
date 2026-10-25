<#
.SYNOPSIS
    Smoke-test the Online Deskew View (BDV) in a disposable Fiji.

.DESCRIPTION
    Installs the freshly built jar into a COPY of Fiji (never the Fiji in daily use), keeping the
    jar it replaces, then starts that Fiji with smoke_online_deskew.groovy. The script opens a raw
    acquisition through the real menu command, paints the BigDataViewer window at two time points
    and in a side view, and closes it the way Fiji's quit does. The persisted OPM preferences are
    backed up and restored around the run, and the test Fiji is halted rather than quit, so it never
    writes its own preferences.

.EXAMPLE
    mvn clean package -DskipTests
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-online-deskew.ps1 -Install
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-online-deskew.ps1 -Quit stay
#>
param(
    [string] $Fiji = 'D:\Fiji_Copy',
    [string] $Raw = 'E:\OPM\3_timelapse_0',
    [string] $Csv = 'E:\OPM\4_beads_for_overlay_0\beads-alignment.csv',
    [string] $Out = (Join-Path ([IO.Path]::GetTempPath()) 'opm-online-deskew-smoke'),
    [int] $TimeoutMinutes = 10,
    # copy target\OPM_Toolset-*.jar into the Fiji's plugins folder first, keeping the replaced jar
    [switch] $Install,
    # the last stage quits Fiji for real (writing ImageJ's preferences, restored afterwards):
    # 'exit' as launched, ImageJ ending the JVM; 'stay' with exitWhenQuitting cleared, as imagej-legacy
    # leaves it, so that the JVM has to end by itself; 'none' to skip it
    [ValidateSet('exit', 'stay', 'none')] [string] $Quit = 'exit',
    # a control run: the same quit, the view never opened
    [switch] $Control
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $Fiji 'fiji-windows-x64.exe'
if (-not (Test-Path $launcher)) { throw "No Fiji launcher at $launcher" }
if ($Fiji -ieq 'D:\Fiji') { throw 'Refusing to install into the Fiji in daily use; give a copy with -Fiji.' }

if ($Install) {
    $built = Get-ChildItem (Join-Path $repo 'target') -Filter 'OPM_Toolset-*.jar' |
        Where-Object { $_.Name -notmatch 'sources|tests' } | Select-Object -First 1
    if (-not $built) { throw 'Build first: mvn clean package -DskipTests' }
    $plugins = Join-Path $Fiji 'plugins'
    foreach ($old in Get-ChildItem $plugins -Filter 'OPM_Toolset-*.jar') {
        $backup = Join-Path $Fiji ($old.Name + '.before-online-deskew')
        if (-not (Test-Path $backup)) { Copy-Item $old.FullName $backup }
        Remove-Item $old.FullName
    }
    Copy-Item $built.FullName (Join-Path $plugins $built.Name)
    Write-Output "Installed $($built.Name) into $plugins (previous jar kept as *.before-online-deskew in $Fiji)"
}

# SciJava preferences live in the registry on Windows; keep every OPM value as it was.
$prefsPath = 'Software\JavaSoft\Prefs\java\lang'
function Get-OpmPrefs {
    $snapshot = @{}
    $root = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($prefsPath)
    if ($null -eq $root) { return $snapshot }
    foreach ($sub in $root.GetSubKeyNames()) {
        $key = $root.OpenSubKey($sub)
        $values = @{}
        foreach ($name in $key.GetValueNames()) { if ($name -like '/O/P/M-*') { $values[$name] = $key.GetValue($name) } }
        $snapshot[$sub] = $values
        $key.Close()
    }
    $root.Close()
    return $snapshot
}
function Restore-OpmPrefs($snapshot) {
    $root = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($prefsPath, $true)
    if ($null -eq $root) { return }
    foreach ($sub in $root.GetSubKeyNames()) {
        $key = $root.OpenSubKey($sub, $true)
        foreach ($name in $key.GetValueNames()) { if ($name -like '/O/P/M-*') { $key.DeleteValue($name) } }
        if ($snapshot.ContainsKey($sub)) { foreach ($name in $snapshot[$sub].Keys) { $key.SetValue($name, $snapshot[$sub][$name]) } }
        $key.Close()
    }
    $root.Close()
}

New-Item -ItemType Directory -Force $Out | Out-Null
$backup = Get-OpmPrefs
# A real quit writes ImageJ's own preferences, shared with every Fiji of this user, and may write
# SciJava's: keep an exact copy of both and put them back afterwards.
$ijPrefs = Join-Path $env:USERPROFILE '.imagej\IJ_Prefs.txt'
$ijPrefsCopy = Join-Path $Out 'IJ_Prefs.before-smoke.txt'
if (Test-Path $ijPrefs) { Copy-Item $ijPrefs $ijPrefsCopy -Force }
$regBefore = Join-Path $Out 'java-prefs.before-smoke.reg'
$regAfter = Join-Path $Out 'java-prefs.after-smoke.reg'
& reg.exe export 'HKCU\Software\JavaSoft\Prefs' $regBefore /y | Out-Null
$env:OPM_SMOKE_RAW = $Raw
$env:OPM_SMOKE_CSV = $Csv
$env:OPM_SMOKE_OUT = $Out
$env:OPM_SMOKE_QUIT = $Quit
$env:OPM_SMOKE_CONTROL = if ($Control) { 'true' } else { 'false' }
$script = Join-Path $PSScriptRoot 'smoke_online_deskew.groovy'
try {
    $process = Start-Process -FilePath $launcher -ArgumentList '--run', "`"$script`"" -PassThru
    if (-not $process.WaitForExit($TimeoutMinutes * 60 * 1000)) {
        Stop-Process -Id $process.Id -Force
        Write-Warning "The smoke test did not finish within $TimeoutMinutes minutes and was stopped."
    } else { Write-Output "Fiji exited with code $($process.ExitCode)." }
} finally {
    Restore-OpmPrefs $backup
    if (Test-Path $ijPrefsCopy) {
        if ((Get-FileHash $ijPrefs).Hash -ne (Get-FileHash $ijPrefsCopy).Hash) {
            Copy-Item $ijPrefsCopy $ijPrefs -Force
            Write-Output "Restored $ijPrefs, which the test Fiji had rewritten."
        } else { Write-Output "$ijPrefs unchanged." }
    }
    & reg.exe export 'HKCU\Software\JavaSoft\Prefs' $regAfter /y | Out-Null
    if ((Get-Item $regBefore).Length -lt 100) {
        Write-Warning "No usable export of the Java preferences at $regBefore; nothing was restored there."
    } elseif ((Get-FileHash $regBefore).Hash -ne (Get-FileHash $regAfter).Hash) {
        & reg.exe delete 'HKCU\Software\JavaSoft\Prefs' /f | Out-Null
        # reg.exe reports success on standard error, which this PowerShell would raise as an error
        & cmd.exe /c "reg import `"$regBefore`" >nul 2>&1"
        & reg.exe export 'HKCU\Software\JavaSoft\Prefs' $regAfter /y | Out-Null
        $same = (Get-FileHash $regBefore).Hash -eq (Get-FileHash $regAfter).Hash
        Write-Output "Java preferences had changed; restored from $regBefore (identical now: $same)."
    } else { Write-Output 'Java preferences unchanged.' }
}
Get-Content (Join-Path $Out 'smoke_report.txt') -ErrorAction SilentlyContinue
