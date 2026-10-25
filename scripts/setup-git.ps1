param(
    [ValidateSet('ziqiang.huang@embl.de', 'hzq.fox@gmail.com')]
    [string]$Email
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Invoke-ProjectGit {
    $result = & git -C $repo @args
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $args" }
    return $result
}

# Preserve either approved address; otherwise use the project owner's default.
if (-not $Email) {
    $configuredEmail = & git -C $repo config --get user.email
    $Email = if ($configuredEmail -in @('ziqiang.huang@embl.de', 'hzq.fox@gmail.com')) {
        $configuredEmail
    } else { 'hzq.fox@gmail.com' }
}

# Preserve other hooks. A custom hooks path needs deliberate integration rather
# than silently writing into a shared/global hook directory or disabling it.
$customHooks = & git -C $repo config --get core.hooksPath
if ($customHooks) {
    throw "A custom core.hooksPath is configured ($customHooks). Integrate the project hook there explicitly; no configuration was changed."
}
$hookDirectory = Invoke-ProjectGit rev-parse --git-path hooks
if (-not [IO.Path]::IsPathRooted($hookDirectory)) {
    $hookDirectory = Join-Path $repo $hookDirectory
}
$hookDirectory = [IO.Path]::GetFullPath($hookDirectory)
$source = Join-Path $PSScriptRoot 'git-hooks\commit-msg'
$destination = Join-Path $hookDirectory 'commit-msg'
New-Item -ItemType Directory -Force -Path $hookDirectory | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Force
if ($env:OS -ne 'Windows_NT') {
    & chmod +x $destination
    if ($LASTEXITCODE -ne 0) { throw 'Could not make the installed hook executable.' }
}
Invoke-ProjectGit config --local user.name 'Ziqiang Huang'
Invoke-ProjectGit config --local user.email $Email
Invoke-ProjectGit config --local user.useConfigOnly true
Write-Output "Configured this repository: Ziqiang Huang <$Email>"
Write-Output "Installed commit policy: $destination"
Invoke-ProjectGit var GIT_AUTHOR_IDENT
Invoke-ProjectGit var GIT_COMMITTER_IDENT
