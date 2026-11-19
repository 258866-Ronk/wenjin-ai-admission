param(
  [Parameter(Mandatory = $true)][string]$Path,
  [string]$Provinces = "河南,广东,江西",
  [string]$Years = "2025",
  [switch]$DryRun
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$dryRunValue = if ($DryRun) { "true" } else { "false" }
& (Join-Path $projectRoot "mvnw.cmd") -pl data-importer -am spring-boot:run "-Dspring-boot.run.arguments=--import.path=$Path --import.provinces=$Provinces --import.years=$Years --import.mode=UPSERT --import.dry-run=$dryRunValue"
