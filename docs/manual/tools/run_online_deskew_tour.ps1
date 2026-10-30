<# Runs only the viewer stage, with a built candidate and private Fiji preferences/plugins. #>
param(
    [string] $Fiji = 'D:\Fiji_Copy',
    [Parameter(Mandatory=$true)] [string] $CandidateJar,
    [Parameter(Mandatory=$true)] [string] $Out,
    [string] $DataRoot = 'E:\OPM',
    [string] $Images = '',
    [int] $TimeoutMinutes = 10
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$candidate = (Resolve-Path -LiteralPath $CandidateJar).Path
if (Test-Path -LiteralPath $Out) { throw 'Use a fresh -Out directory; previous evidence is never replaced.' }
New-Item -ItemType Directory -Path $Out | Out-Null
$Out = (Resolve-Path -LiteralPath $Out).Path
if (-not $Images) { $Images = Join-Path $repo 'docs\manual\img\online-deskew' }
New-Item -ItemType Directory -Force -Path $Images | Out-Null
$Images = (Resolve-Path -LiteralPath $Images).Path
$homeDir = Join-Path $Out 'home'
$classes = Join-Path $Out 'prefs-factory'
New-Item -ItemType Directory -Path $homeDir,$classes | Out-Null
$jdk = (Get-ChildItem -LiteralPath (Join-Path $Fiji 'java') -Recurse -Filter javac.exe | Select-Object -First 1).Directory.FullName
& (Join-Path $jdk 'javac.exe') -d $classes (Join-Path $repo 'scripts\smoke\IsolatedPreferencesFactory.java')
if ($LASTEXITCODE -ne 0) { throw 'Preferences factory did not compile' }
$plugins = Join-Path $Out 'plugins'
Copy-Item -LiteralPath (Join-Path $Fiji 'plugins') -Destination $plugins -Recurse
Get-ChildItem -LiteralPath $plugins -Filter 'OPM_Toolset-*.jar' | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
$loadedCandidate = Join-Path $plugins (Split-Path -Leaf $candidate)
Copy-Item -LiteralPath $candidate -Destination $loadedCandidate
& (Join-Path $jdk 'jar.exe') cf (Join-Path $plugins 'opm-tour-isolated-prefs.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Preferences factory did not package' }
$env:OPM_TOUR_REPO = $repo
$env:OPM_TOUR_STAGES = 'viewer'
$env:OPM_TOUR_DATA = $DataRoot
$env:OPM_TOUR_REPORT = Join-Path $Out 'report.txt'
$env:OPM_TOUR_SCRIPT = Join-Path $PSScriptRoot 'live_deskew_tour.groovy'
$env:OPM_TOUR_CANDIDATE = $loadedCandidate
$env:OPM_TOUR_HOME = $homeDir
$env:OPM_TOUR_VIEWER_IMAGES = $Images
$boot = Join-Path $PSScriptRoot 'tour_boot.groovy'
$argsForFiji = @('--plugins', "`"$plugins`"", '--heap=8g', "`"-Duser.home=$homeDir`"",
    '-Djava.util.prefs.PreferencesFactory=opm.smoke.IsolatedPreferencesFactory', '--run', "`"$boot`"")
Write-Output "Candidate $candidate SHA256 $((Get-FileHash -LiteralPath $candidate).Hash)"
$process = Start-Process -FilePath (Join-Path $Fiji 'fiji-windows-x64.exe') -ArgumentList $argsForFiji -WindowStyle Hidden -PassThru
if (-not $process.WaitForExit($TimeoutMinutes * 60 * 1000)) {
    Stop-Process -Id $process.Id -Force
    throw "Viewer tour timed out after $TimeoutMinutes minutes"
}
$text = Get-Content -LiteralPath $env:OPM_TOUR_REPORT -Raw
Write-Output $text
if ($process.ExitCode -ne 0 -or $text -notmatch 'VIEWER TOUR PASSED' -or $text -match 'TOUR FAILED|ERROR stage') {
    throw 'Viewer tour did not pass; keep this output and inspect report.txt'
}
foreach ($name in @('setup','help','completed-xy','completed-xz','whole')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Images "$name.png"))) { throw "Missing viewer screenshot: $name" }
}
& python (Join-Path $PSScriptRoot 'optimize_images.py') $Images
if ($LASTEXITCODE -ne 0) { throw 'Image optimization failed' }
Write-Output 'Viewer-only painted tour passed; Fiji installation and shared preferences were not written.'
