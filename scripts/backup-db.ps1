<#
.SYNOPSIS
    PulseChat Database Backup Script for Windows PowerShell
.DESCRIPTION
    Creates compressed backups of either the PostgreSQL Docker container or the local H2 file database.
#>

param(
    [string]$BackupDir = ".\backups",
    [int]$RetentionDays = 14
)

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

Write-Host "=== [PulseChat] Starting Database Backup: $Timestamp ===" -ForegroundColor Cyan

# Check if Docker container is available
$dockerRunning = $false
try {
    $containers = docker ps --format "{{.Names}}" 2>$null
    if ($containers -match "pulsechat-postgres") {
        $dockerRunning = $true
    }
} catch {
    $dockerRunning = $false
}

if ($dockerRunning) {
    $BackupFile = Join-Path $BackupDir "pulsechat_pg_$Timestamp.sql"
    Write-Host "Dumping PostgreSQL from container 'pulsechat-postgres'..." -ForegroundColor Green
    docker exec pulsechat-postgres pg_dump -U postgres -d pulsechatdb > $BackupFile
    Write-Host "Backup created: $BackupFile" -ForegroundColor Green
} elseif (Test-Path ".\data\chatdb.mv.db") {
    $ZipFile = Join-Path $BackupDir "pulsechat_h2_$Timestamp.zip"
    Write-Host "Backing up local H2 database files to $ZipFile..." -ForegroundColor Green
    Compress-Archive -Path ".\data\chatdb*.db" -DestinationPath $ZipFile -Force
    Write-Host "Backup created: $ZipFile" -ForegroundColor Green
} else {
    Write-Warning "No active database found to back up."
    exit 1
}

# Retention cleanup
$CutoffDate = (Get-Date).AddDays(-$RetentionDays)
Get-ChildItem -Path $BackupDir -Filter "pulsechat_*" | Where-Object { $_.LastWriteTime -lt $CutoffDate } | ForEach-Object {
    Write-Host "Removing expired backup: $($_.Name)" -ForegroundColor Yellow
    Remove-Item $_.FullName -Force
}

Write-Host "=== [PulseChat] Backup completed successfully ===" -ForegroundColor Cyan
