<#
.SYNOPSIS
    Run the Online Deskew View's sampler and image-family micro-benchmark (joint contract C9.3)
    on both Java stacks.

.DESCRIPTION
    One JVM per configuration, so that each image family is first measured on its own - the
    sampling code's call sites see one family - and then all together, round-robin in one JVM.
    Stacks: JDK 8 with the Maven-managed libraries (BDV 10.6.4, ImgLib2 7.1.5), and a Fiji's own
    Java and jars (BDV 10.6.11, ImgLib2 8). Writes every RESULT line to results.tsv in -Out.

    Build first (test classes are needed): mvn -o clean test-compile

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sampler-benchmark.ps1
#>
param(
    [string] $Fiji = 'D:\Fiji_Copy',
    [string] $Jdk8 = 'D:\Git\OPM_toolset\.tools\jdk8',
    [string] $Mvn = 'C:\Tools\apache-maven-3.9.12\bin\mvn.cmd',
    [string] $Out = (Join-Path ([IO.Path]::GetTempPath()) 'opm-sampler-benchmark'),
    [string[]] $Families = @('blocking', 'volatile', 'cell', 'planar', 'array'),
    [string[]] $Samplers = @('clamping', 'nearest', 'nlinear'),
    [string[]] $Views = @('xy', 'xz'),
    [int] $Rounds = 15,
    [int] $Warmup = 5
)
$ErrorActionPreference = 'Stop'
# with powershell -File, "-Families a,b" arrives as the single string "a,b"
$Families = @($Families -split ',' | Where-Object { $_ })
$Samplers = @($Samplers -split ',' | Where-Object { $_ })
$Views = @($Views -split ',' | Where-Object { $_ })
$repo = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force $Out | Out-Null
$data = Join-Path $Out 'data'

# JDK 8 class path: Maven's resolved dependencies behind a manifest-only jar (JDK 8 has no @argfiles)
$cpFile = Join-Path $Out 'maven-classpath.txt'
$env:JAVA_HOME = $Jdk8
& $Mvn -o -q -f (Join-Path $repo 'pom.xml') dependency:build-classpath "-Dmdep.outputFile=$cpFile" | Out-Null
$entries = @((Join-Path $repo 'target\classes'), (Join-Path $repo 'target\test-classes')) + ((Get-Content $cpFile -Raw).Trim() -split ';')
$urls = $entries | ForEach-Object { ([Uri](Resolve-Path $_).Path).AbsoluteUri + $(if (Test-Path $_ -PathType Container) { '/' } else { '' }) }
$manifest = "Manifest-Version: 1.0`r`nClass-Path: " + (($urls -join ' ') -replace '(.{60})', "`$1`r`n ") + "`r`n"
$manifestFile = Join-Path $Out 'MANIFEST.MF'
[IO.File]::WriteAllText($manifestFile, $manifest)
$pathingJar = Join-Path $Out 'classpath-jdk8.jar'
& (Join-Path $Jdk8 'bin\jar.exe') cfm $pathingJar $manifestFile
$stacks = @(
    @{ name = 'jdk8'; java = (Join-Path $Jdk8 'bin\java.exe'); cp = $pathingJar },
    @{ name = 'fiji-jdk21'; java = (Get-ChildItem (Join-Path $Fiji 'java') -Recurse -Filter 'java.exe' | Select-Object -First 1).FullName;
       cp = (@((Join-Path $repo 'target\classes'), (Join-Path $repo 'target\test-classes'), (Join-Path $Fiji 'jars\*'),
               (Join-Path $Fiji 'plugins\*')) -join ';') }
)

$results = Join-Path $Out 'results.tsv'
"stack`tjava`tfamily`tsampler`tview`tmix`tmedian_ns`tp95_ns`tmin_ns" | Set-Content $results
function Invoke-Bench($stack, [string] $families, [string] $sampler, [string] $view) {
    $output = & $stack.java '-Xmx3g' '-cp' $stack.cp 'de.embl.iclm.RawSamplerBenchmark' "families=$families" "sampler=$sampler" `
        "view=$view" "rounds=$Rounds" "warmup=$Warmup" "data=$data" 2>&1
    foreach ($line in $output) {
        $text = "$line"
        if ($text -match '^RESULT java=(\S+) family=(\S+) sampler=(\S+) view=(\S+) (.+) median=(\S+) p95=(\S+) min=(\S+) ns') {
            "$($stack.name)`t$($Matches[1])`t$($Matches[2])`t$($Matches[3])`t$($Matches[4])`t$($Matches[5])`t$($Matches[6])`t$($Matches[7])`t$($Matches[8])" |
                Add-Content $results
            Write-Output $text
        } elseif ($text -match 'Exception|Error') { Write-Warning $text }
    }
}
foreach ($stack in $stacks) {
    foreach ($view in $Views) {
        foreach ($sampler in $Samplers) {
            foreach ($family in $Families) { Invoke-Bench $stack $family $sampler $view }
        }
        # all copied and cached families together, round-robin in one JVM: mixed call sites
        Invoke-Bench $stack (($Families | Where-Object { $_ -ne 'volatile' }) -join ',') 'clamping' $view
    }
}
Write-Output "Results: $results"
