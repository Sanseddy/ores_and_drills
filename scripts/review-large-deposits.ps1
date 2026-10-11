param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$reportRoot = Join-Path $projectRoot 'build/reports/factorio-deposits'
$classes = Join-Path $reportRoot 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = @('SeedMixer', 'DepositTier', 'DepositTierMath', 'BoundedLoadingCache', 'CompactDepositGeometry') | ForEach-Object {
    Join-Path $projectRoot "src/main/java/dev/world/level/levelgen/$_.java"
}
$sources += @('LargeDepositReview', 'CompactDepositReview', 'FactorioSourceModelReference') | ForEach-Object {
    Join-Path $projectRoot "src/test/java/dev/world/level/levelgen/$_.java"
}
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Review compilation failed' }
& "$JavaHome/bin/java.exe" -Xms512m -Xmx512m '-XX:+UseSerialGC' '-Djava.awt.headless=true' -cp $classes dev.world.level.levelgen.LargeDepositReview $reportRoot
if ($LASTEXITCODE -ne 0) { throw 'Geometry audit failed' }
& "$JavaHome/bin/java.exe" '-Djava.awt.headless=true' -cp $classes dev.world.level.levelgen.FactorioSourceModelReference "$reportRoot/factorio-reference"
if ($LASTEXITCODE -ne 0) { throw 'Reference generation failed' }
Copy-Item -LiteralPath "$PSScriptRoot/deposit-viewer.html" -Destination "$reportRoot/index.html"
Write-Output "Review: $reportRoot/index.html"
