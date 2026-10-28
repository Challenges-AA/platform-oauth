$ErrorActionPreference = 'Stop'
# Produit un rapport daté ; la présence d'un outil ne garantit pas l'accès à sa base CVE.
$root = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $root 'docs/evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path $evidence "cve-scan-$stamp.txt"
$tools = @('trivy','grype')
$available = @($tools | Where-Object { Get-Command $_ -ErrorAction SilentlyContinue })
if ($available.Count -eq 0) { "INCOMPLETE: neither Trivy nor Grype is installed; no current vulnerability scan was performed." | Set-Content $report; Write-Error 'Install Trivy or Grype to scan dependencies and images.' }
$failed = $false
# Exécute tous les scanners installés sur les dépendances locales et les images construites.
foreach ($tool in $available) {
  "Tool: $tool`nDate: $(Get-Date -Format o)`nScope: local Maven dependency tree and delivered images (if built)`n" | Add-Content $report
  if ($tool -eq 'trivy') {
    & trivy fs --scanners vuln --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL --exit-code 1 --format table $root 2>&1 | Add-Content $report
    if ($LASTEXITCODE -ne 0) { $failed=$true }
    foreach ($image in @('portage-key-service:local','portage-authorization-server:local','portage-resource-server:local')) { & trivy image --exit-code 1 $image 2>&1 | Add-Content $report; if ($LASTEXITCODE -ne 0) { $failed=$true } }
  } else {
    & grype dir:$root --fail-on low 2>&1 | Add-Content $report
    if ($LASTEXITCODE -ne 0) { $failed=$true }
    foreach ($image in @('portage-key-service:local','portage-authorization-server:local','portage-resource-server:local')) { & grype $image --fail-on low 2>&1 | Add-Content $report; if ($LASTEXITCODE -ne 0) { $failed=$true } }
  }
}
if ($failed) { throw "CVE scan incomplete or findings present; inspect $report" }
Write-Output "Scan report: $report"
