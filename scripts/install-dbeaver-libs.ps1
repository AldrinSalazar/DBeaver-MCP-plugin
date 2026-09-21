<#
.SYNOPSIS
  Registers the DBeaver jars needed by the unit tests in your local Maven repo.
  The plugin itself resolves DBeaver/Eclipse APIs from p2 (see root pom.xml).
.EXAMPLE
  .\install-dbeaver-libs.ps1
  .\install-dbeaver-libs.ps1 -DbeaverDir "D:\DBeaver"
#>
param(
  [string]$DbeaverDir = "C:\Program Files\DBeaver"
)

$ErrorActionPreference = "Stop"

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
  Write-Host "Maven (mvn) not found. Install it first, e.g.: winget install Apache.Maven" -ForegroundColor Red
  exit 1
}

$pluginsDir = Join-Path $DbeaverDir "plugins"
if (-not (Test-Path $pluginsDir)) {
  Write-Host "Plugins directory not found: $pluginsDir" -ForegroundColor Red
  Write-Host "Pass your DBeaver install dir: .\install-dbeaver-libs.ps1 -DbeaverDir <path>"
  exit 1
}

$bundles = @(
  "org.jkiss.dbeaver.model",
  "org.jkiss.utils"
)

$installed = 0
foreach ($bundle in ($bundles | Select-Object -Unique)) {
  $jar = Get-ChildItem -Path $pluginsDir -Filter "$bundle_*.jar" |
    Sort-Object Name -Descending | Select-Object -First 1
  if (-not $jar) {
    Write-Host "WARNING: no jar found for $bundle" -ForegroundColor Yellow
    continue
  }
  Write-Host "Installing $bundle <- $($jar.Name)"
  & mvn -q install:install-file `
    "-DgroupId=dbeaver.local" `
    "-DartifactId=$bundle" `
    "-Dversion=1.0.0-local" `
    "-Dpackaging=jar" `
    "-Dfile=$($jar.FullName)"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $installed++
}

Write-Host ""
Write-Host "Done: $installed bundle(s) registered. Now run: mvn clean verify" -ForegroundColor Green
