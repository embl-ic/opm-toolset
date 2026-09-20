<#
.SYNOPSIS
    Regenerate the Live Deskew manual's screenshots and data figures.

.DESCRIPTION
    Starts a separate Fiji that runs live_deskew_tour.groovy, then compresses the images.
    Your own Fiji can stay open. The tour needs the example acquisitions (see -DataRoot) and
    writes its results under -TutorialRoot, where the raw TIFFs are hard links (same disk).

    The Live Deskew settings the tour types into the setup dialog are persisted by the plugin
    exactly as a user's would be, so they are backed up first and put back afterwards.

.EXAMPLE
    ./docs/manual/tools/run_tour.ps1
    ./docs/manual/tools/run_tour.ps1 -Stages help
    ./docs/manual/tools/run_tour.ps1 -Stages sample -Timepoints 4
#>
param(
    [string] $Stages = 'prepare,help,beads,align,sample',
    [int] $Timepoints = 6,
    [int] $Port = 5020,
    [string] $Fiji = 'D:\Fiji\fiji-windows-x64.exe',
    [string] $DataRoot = 'E:\OPM',
    [string] $TutorialRoot = 'E:\OPM\tutorial',
    [int] $TimeoutMinutes = 90,
    # Link the extra time points without clearing what a previous run wrote, so the run resumes
    # instead of starting over: a quick way to refresh the run's own screenshots.
    [switch] $KeepResults
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$work = Join-Path ([IO.Path]::GetTempPath()) 'opm-manual-tour'
New-Item -ItemType Directory -Force $work | Out-Null

# 1. The help box is shown from the source tree's Help.java, not from the jar installed in Fiji,
#    so the screenshot matches what the next build ships. Help has no dependencies.
$helpClasses = Join-Path $work 'help-classes'
Remove-Item -Recurse -Force $helpClasses -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $helpClasses | Out-Null
$javac = Join-Path $repo '.tools\jdk8\bin\javac.exe'
if (-not (Test-Path $javac)) { $javac = 'javac' }
& $javac -encoding UTF-8 -d $helpClasses (Join-Path $repo 'src\main\java\de\embl\iclm\Help.java')
if ($LASTEXITCODE -ne 0) { throw 'Help.java did not compile' }

# 2. Back up every persisted OPM value (SciJava prefs live in the registry on Windows).
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
$backup = Get-OpmPrefs
$backup | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $work 'opm-prefs-backup.json')

# 3. Run the tour in its own Fiji.
$report = Join-Path $work 'report.txt'
$env:OPM_TOUR_REPO = $repo
$env:OPM_TOUR_STAGES = $Stages
$env:OPM_TOUR_TIMEPOINTS = "$Timepoints"
$env:OPM_TOUR_PORT = "$Port"
$env:OPM_TOUR_DATA = $DataRoot
$env:OPM_TOUR_ROOT = $TutorialRoot
$env:OPM_TOUR_REPORT = $report
$env:OPM_TOUR_HELP_CLASSES = $helpClasses
$env:OPM_TOUR_KEEP_RESULTS = if ($KeepResults) { '1' } else { '' }
$env:OPM_TOUR_SCRIPT = Join-Path $PSScriptRoot 'live_deskew_tour.groovy'
$boot = Join-Path $PSScriptRoot 'tour_boot.groovy'
try {
    $process = Start-Process -FilePath $Fiji -ArgumentList '--run', "`"$boot`"" -PassThru
    if (-not $process.WaitForExit($TimeoutMinutes * 60 * 1000)) {
        Stop-Process -Id $process.Id -Force
        Write-Warning "The tour did not finish within $TimeoutMinutes minutes and was stopped."
    }
} finally {
    Restore-OpmPrefs $backup
}
Get-Content $report -ErrorAction SilentlyContinue

# 4. Palette-compress the screenshots (keeps a file only where it gets smaller).
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) { & python (Join-Path $PSScriptRoot 'optimize_images.py') (Join-Path $repo 'docs\manual\img') }
