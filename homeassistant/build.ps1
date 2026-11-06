<#
.SYNOPSIS
Build and stage the Home Assistant local app without touching the running JAR.
.DESCRIPTION
Requires Git, Node/npm, JDK 21 and network access for missing dependencies.
Only the reported local_apps/receiptshooter directory is a deployment bundle.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$git = (Get-Command git.exe -ErrorAction Stop).Source
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\jar.exe'))) {
    throw 'Set JAVA_HOME to a JDK 21 installation before running this script.'
}
$jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$id = [guid]::NewGuid().ToString('N')
$workRoot = Join-Path ([IO.Path]::GetTempPath()) "receiptshooter-build-$id"
$outputRoot = Join-Path $PSScriptRoot ("build\{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $id.Substring(0, 8))
$stage = Join-Path $outputRoot 'local_apps\receiptshooter'
New-Item -ItemType Directory -Path $workRoot | Out-Null

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Program)) failed with exit code $LASTEXITCODE." }
}

try {
    # Copy only Git-tracked or unignored source. In particular, never copy
    # certificates, .env files, local database files, backups or build output.
    Push-Location $repoRoot
    try {
        $sources = @(& $git ls-files --cached --others --exclude-standard -- `
            'backend/pom.xml' 'backend/mvnw' 'backend/mvnw.cmd' 'backend/.mvn/wrapper/maven-wrapper.properties' 'backend/src' `
            'frontend/package.json' 'frontend/package-lock.json' 'frontend/index.html' 'frontend/tsconfig*.json' `
            'frontend/vite.config.ts' 'frontend/vitest.config.ts' 'frontend/src' 'frontend/public' 'frontend/test')
        if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate the project source with Git.' }
    } finally { Pop-Location }

    foreach ($relative in ($sources | Select-Object -Unique)) {
        $source = Join-Path $repoRoot $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $item = Get-Item -LiteralPath $source -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Refusing a linked source file: $relative" }
        $destination = Join-Path $workRoot $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
    foreach ($required in @('frontend/package-lock.json', 'frontend/index.html', 'backend/pom.xml', 'backend/mvnw.cmd')) {
        if (-not (Test-Path -LiteralPath (Join-Path $workRoot $required) -PathType Leaf)) { throw "Missing build input: $required" }
    }

    Push-Location (Join-Path $workRoot 'frontend')
    try {
        Invoke-Checked $npm @('ci', '--no-audit', '--no-fund')
        Invoke-Checked $npm @('test')
        Invoke-Checked $npm @('run', 'build')
    } finally { Pop-Location }

    # MariaDB integration tests opt in via this variable and can alter their
    # database. Never run them against a household database during packaging.
    $testUrlNames = @('MARIADB_TEST_URL', 'MARIADB_MIGRATION_TEST_URL', 'MARIADB_SESSION_TEST_URL')
    $previousTestUrls = @{}
    foreach ($name in $testUrlNames) {
        $previousTestUrls[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Push-Location (Join-Path $workRoot 'backend')
    try {
        Invoke-Checked (Join-Path $workRoot 'backend\mvnw.cmd') @('-B', 'clean', 'verify')
    } finally {
        Pop-Location
        foreach ($name in $testUrlNames) {
            [Environment]::SetEnvironmentVariable($name, $previousTestUrls[$name], 'Process')
        }
    }

    $jar = Join-Path $workRoot 'backend\target\receiptshooter-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'The executable backend JAR was not produced.' }
    $entries = @(& $jarTool tf $jar)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the executable JAR.' }
    foreach ($required in @('BOOT-INF/classes/static/index.html', 'BOOT-INF/classes/static/sw.js', 'BOOT-INF/classes/static/manifest.webmanifest')) {
        if ($entries -notcontains $required) { throw "The integrated JAR is missing $required." }
    }
    $unexpected = @($entries | Where-Object { $_ -match '(^|/)(\.env[^/]*|application-local\.(yaml|yml|properties)|credentials\.json|secrets[^/]*\.json|private-backups)(/|$)|\.(pem|key|p12|pfx|jks|dpapi|sqlite|db)$' })
    if ($unexpected.Count -ne 0) { throw 'The JAR contains a private or local-configuration filename; inspect it before deploying.' }

    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    foreach ($file in @('config.yaml', 'Dockerfile', 'run.sh', '.dockerignore', 'README.md', 'DOCS.md')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "receiptshooter\$file") -Destination (Join-Path $stage $file)
    }
    Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'receiptshooter.jar')
    $hash = (Get-FileHash -LiteralPath (Join-Path $stage 'receiptshooter.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $stage 'receiptshooter.jar.sha256'), "$hash  receiptshooter.jar`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Deployment bundle: $stage"
    Write-Host "JAR SHA-256: $hash"
    Write-Host 'Only transfer this receiptshooter folder to Home Assistant local_apps.'
} finally {
    # Delete only the unique temporary directory created by this invocation.
    if (Test-Path -LiteralPath $workRoot) {
        $resolved = (Resolve-Path -LiteralPath $workRoot).ProviderPath
        $tempRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).ProviderPath.TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            (Split-Path -Leaf $resolved) -ne "receiptshooter-build-$id" -or
            ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refusing to remove an unexpected temporary build path.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
