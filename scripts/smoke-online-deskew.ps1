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
#>
param(
    [string] $Fiji = 'D:\Fiji_Copy',
    [string] $Raw = 'E:\OPM\3_timelapse_0',
    [string] $Csv = 'E:\OPM\4_beads_for_overlay_0\beads-alignment.csv',
    [string] $Out = (Join-Path ([IO.Path]::GetTempPath()) 'opm-online-deskew-smoke'),
    [int] $TimeoutMinutes = 10,
    # copy target\OPM_Toolset-*.jar into the Fiji's plugins folder first, keeping the replaced jar
    [switch] $Install
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
$env:OPM_SMOKE_RAW = $Raw
$env:OPM_SMOKE_CSV = $Csv
$env:OPM_SMOKE_OUT = $Out
$script = Join-Path $PSScriptRoot 'smoke_online_deskew.groovy'
try {
    $process = Start-Process -FilePath $launcher -ArgumentList '--run', "`"$script`"" -PassThru
    if (-not $process.WaitForExit($TimeoutMinutes * 60 * 1000)) {
        Stop-Process -Id $process.Id -Force
        Write-Warning "The smoke test did not finish within $TimeoutMinutes minutes and was stopped."
    }
} finally {
    Restore-OpmPrefs $backup
}
Get-Content (Join-Path $Out 'smoke_report.txt') -ErrorAction SilentlyContinue
