<#
.SYNOPSIS
    Smoke-test the Online Deskew View (BDV) in a disposable, isolated Fiji.

.DESCRIPTION
    Installs the freshly built jar into a COPY of Fiji (never the Fiji in daily use), keeping the
    jar it replaces, then starts that Fiji with smoke_online_deskew.groovy. The script opens a raw
    acquisition through the real menu command, paints the window at two time points and in a side
    view, presses its close button while timing the event thread, opens it again and quits Fiji
    for real.

    The test Fiji is ISOLATED from this user's preferences rather than having them restored
    afterwards - restoring would undo whatever another running Fiji wrote meanwhile:
      - user.home points at a folder of the run, so ImageJ's IJ_Prefs.txt (and any other
        per-user file Fiji keeps there) is written there;
      - Java preferences, which on Windows live in the registry shared by every Java program of
        the user, are kept in memory by smoke\IsolatedPreferencesFactory.java, compiled here and
        put on the test Fiji's class path for the run only.
    The Groovy script checks that both are in effect before it opens anything, and stops if not.
    Afterwards this script only checks the shared stores - the user's IJ_Prefs.txt and the registry
    key of the run's marker - and never writes to them.

    With -Grow the view opens a copy instead, which grows while the smoke runs (M3-A): -GrowStart
    time points of acquisition channel 1 - time 1, then time 3 on, time 2 held back - are copied into
    a new folder under -Grow, and the Groovy script copies the next -GrowAdd in as an acquisition
    writes them, then time 2, late. The copy is deleted when Fiji has exited. Each file of
    3_timelapse_0 is 1.44 GB: 15.9 GB at the defaults.

.EXAMPLE
    mvn clean package -DskipTests
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-online-deskew.ps1 -Install -Quit none -Control
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-online-deskew.ps1 -Quit exit
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-online-deskew.ps1 -Quit stay
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-online-deskew.ps1 -Quit exit -Grow D:\Temp
#>
param(
    [string] $Fiji = 'D:\Fiji_Copy',
    [string] $Raw = 'E:\OPM\3_timelapse_0',
    [string] $Csv = 'E:\OPM\4_beads_for_overlay_0\beads-alignment.csv',
    [string] $Out = (Join-Path ([IO.Path]::GetTempPath()) 'opm-online-deskew-smoke'),
    [int] $TimeoutMinutes = 10,
    # copy target\OPM_Toolset-*.jar into the Fiji's plugins folder first, keeping the replaced jar
    [switch] $Install,
    # the last stage quits Fiji for real: 'exit' as launched, ImageJ ending the JVM; 'stay' with
    # exitWhenQuitting cleared, as imagej-legacy leaves it, so that the JVM has to end by itself;
    # 'none' to skip it
    [ValidateSet('exit', 'stay', 'none')] [string] $Quit = 'exit',
    # a control run: the same quit, the view never opened
    [switch] $Control,
    # a folder for a copy of the acquisition that grows during the smoke (M3-A); empty: open -Raw where it is
    [string] $Grow = '',
    # time points the copy starts with (time 2 held back) - at least eight, as the completed playback stage
    # plays from the sixth to the last and reads its rate from two frames or more - and later ones copied in
    # while the view is open
    [int] $GrowStart = 8,
    [int] $GrowAdd = 2
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

# The run's own home and its in-memory Java preferences.
New-Item -ItemType Directory -Force $Out | Out-Null
$isolatedHome = Join-Path $Out 'home'
if (Test-Path $isolatedHome) { Remove-Item -Recurse -Force $isolatedHome }
New-Item -ItemType Directory $isolatedHome | Out-Null
$jdk = (Get-ChildItem (Join-Path $Fiji 'java') -Recurse -Filter 'javac.exe' | Select-Object -First 1).Directory.FullName
$classes = Join-Path $Out 'prefs-factory'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory $classes | Out-Null
& (Join-Path $jdk 'javac.exe') -d $classes (Join-Path $PSScriptRoot 'smoke\IsolatedPreferencesFactory.java')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the isolated preferences factory' }
$factoryJar = Join-Path $Fiji 'jars\opm-smoke-isolated-prefs.jar'
& (Join-Path $jdk 'jar.exe') cf $factoryJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Could not package the isolated preferences factory' }

$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$growing = $null
$env:OPM_SMOKE_GROW_FILES = ''
if ($Grow) {
    $byTime = @(Get-ChildItem $Raw -Filter '*_Channel0001_*.tiff' | ForEach-Object {
        if ($_.Name -match '_Time(\d+)_') { [pscustomobject]@{ Time = [int]$Matches[1]; File = $_ } } } | Sort-Object Time)
    if ($GrowStart -lt 8) { throw '-GrowStart must be at least 8: the completed playback stage plays two frames or more after the sixth time point' }
    if ($byTime.Count -lt $GrowStart + $GrowAdd + 1) { throw "Not enough time points in $Raw to grow a copy" }
    $growing = Join-Path $Grow ('opm-smoke-growing-' + $runId)
    New-Item -ItemType Directory $growing | Out-Null
    Copy-Item (Join-Path $Raw 'ExperimentalParameters.txt') $growing
    $initial = @($byTime[0]) + @($byTime[2..$GrowStart])		# time 1 and 3 to GrowStart + 1: time 2 arrives last
    $later = @($byTime[($GrowStart + 1)..($GrowStart + $GrowAdd)]) + @($byTime[1])
    foreach ($t in $initial) { Copy-Item $t.File.FullName $growing }
    $env:OPM_SMOKE_GROW_FILES = ($later | ForEach-Object { $_.File.FullName }) -join ';'
    Write-Output "Growing copy $growing starts with time points $(($initial | ForEach-Object { $_.Time }) -join ', '); adds $(($later | ForEach-Object { $_.Time }) -join ', ') during the smoke"
    $Raw = $growing
}
$sharedIjPrefs = Join-Path $env:USERPROFILE '.imagej\IJ_Prefs.txt'
$ijBefore = if (Test-Path $sharedIjPrefs) { (Get-FileHash $sharedIjPrefs).Hash } else { 'absent' }
$env:OPM_SMOKE_RAW = $Raw
$env:OPM_SMOKE_CSV = $Csv
$env:OPM_SMOKE_OUT = $Out
$env:OPM_SMOKE_QUIT = $Quit
$env:OPM_SMOKE_CONTROL = if ($Control) { 'true' } else { 'false' }
$env:OPM_SMOKE_HOME = $isolatedHome
$env:OPM_SMOKE_RUN_ID = $runId
$script = Join-Path $PSScriptRoot 'smoke_online_deskew.groovy'
try {
    $arguments = @("`"-Duser.home=$isolatedHome`"", '-Djava.util.prefs.PreferencesFactory=opm.smoke.IsolatedPreferencesFactory',
        '--run', "`"$script`"")
    $process = Start-Process -FilePath $launcher -ArgumentList $arguments -WindowStyle Hidden -PassThru
    if (-not $process.WaitForExit($TimeoutMinutes * 60 * 1000)) {
        Stop-Process -Id $process.Id -Force
        Write-Warning "The smoke test did not finish within $TimeoutMinutes minutes and was stopped."
    } else { Write-Output "Fiji exited with code $($process.ExitCode)." }
} finally {
    Remove-Item $factoryJar -Force -ErrorAction SilentlyContinue
    if ($growing) {
        Remove-Item -Recurse -Force $growing -ErrorAction SilentlyContinue
        if (Test-Path $growing) { Write-Warning "The growing copy $growing could not be deleted" } else { Write-Output "Deleted the growing copy $growing." }
    }
}

# Check, never restore: the shared stores must show nothing of this run.
$ijAfter = if (Test-Path $sharedIjPrefs) { (Get-FileHash $sharedIjPrefs).Hash } else { 'absent' }
if ($ijAfter -eq $ijBefore) { Write-Output "Shared $sharedIjPrefs unchanged." }
else { Write-Warning "Shared $sharedIjPrefs changed during the run; this run does not write it, so another program did. Left as it is." }
$isolatedIj = Join-Path $isolatedHome '.imagej\IJ_Prefs.txt'
if (Test-Path $isolatedIj) {
    $marked = Select-String -Path $isolatedIj -Pattern "opm.smoke.run=$runId" -SimpleMatch -Quiet
    Write-Output "The test Fiji wrote its ImageJ preferences into the run's home (marker present: $marked)."
} elseif ($Quit -ne 'none') { Write-Warning "No ImageJ preferences in the run's home after a quit: $isolatedIj" }
& cmd.exe /c "reg query HKCU\Software\JavaSoft\Prefs\opm-smoke-isolation >nul 2>&1"
if ($LASTEXITCODE -eq 0) { Write-Warning 'The run''s marker reached the shared Java preferences: isolation FAILED.' }
else { Write-Output 'The run''s marker is not in the shared Java preferences.' }
Get-Content (Join-Path $Out 'smoke_report.txt') -ErrorAction SilentlyContinue
