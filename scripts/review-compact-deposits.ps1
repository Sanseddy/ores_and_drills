param([string]$JavaHome = $env:JAVA_HOME, [switch]$RecordProfile)
# Compatibility entry point: the old 3..7 block implementation is historical.
Write-Output 'Current 30..70 block audit: build/reports/factorio-deposits'
& "$PSScriptRoot/review-large-deposits.ps1" -JavaHome $JavaHome
