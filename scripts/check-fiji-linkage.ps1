# Run the Online Deskew View's classes against an installed Fiji's own jars and Java, read-only.
#
# The Maven build compiles against pom-scijava's managed BigDataViewer and ImgLib2; Fiji ships
# newer releases on Java 21. This launches OnlineDeskewLinkageCheck with the freshly built classes
# first on the class path, then Fiji's jars and plugins (so an older OPM_Toolset jar in Fiji is
# shadowed, not used). Nothing in the Fiji folder is written.
#
#   mvn clean test
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-fiji-linkage.ps1 -Fiji D:\Fiji_Copy
param(
    [string]$Fiji = 'D:\Fiji_Copy'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $repo 'target\classes'
$tests = Join-Path $repo 'target\test-classes'
if (-not (Test-Path (Join-Path $tests 'de\embl\iclm\OnlineDeskewLinkageCheck.class'))) {
    throw "Build first (mvn clean test): $tests has no OnlineDeskewLinkageCheck.class"
}
$java = Get-ChildItem -Path (Join-Path $Fiji 'java') -Recurse -Filter 'java.exe' |
    Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
if (-not $java) { throw "No Java found under $Fiji\java" }

$classPath = @($classes, $tests, (Join-Path $Fiji 'jars\*'), (Join-Path $Fiji 'plugins\*')) -join ';'
Write-Output "Fiji: $Fiji"
Write-Output "Java: $($java.FullName)"
& $java.FullName '-Djava.awt.headless=true' -cp $classPath de.embl.iclm.OnlineDeskewLinkageCheck
exit $LASTEXITCODE
