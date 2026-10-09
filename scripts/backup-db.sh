#!/usr/bin/env bash
# ==============================================================================
# PulseChat Database Automated Backup Script
# Usage: ./scripts/backup-db.sh
# Can be scheduled via cron (e.g., 0 2 * * * for 2 AM daily backups)
# ==============================================================================

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"

echo "=== [PulseChat] Starting Database Backup: $TIMESTAMP ==="

# Check if Docker compose PostgreSQL container is running
if docker ps --format '{{.Names}}' | grep -q "pulsechat-postgres"; then
    BACKUP_FILE="$BACKUP_DIR/pulsechat_pg_$TIMESTAMP.sql.gz"
    echo "Dumping PostgreSQL from container 'pulsechat-postgres'..."
    docker exec pulsechat-postgres pg_dump -U postgres -d pulsechatdb | gzip > "$BACKUP_FILE"
    echo "Backup completed: $BACKUP_FILE (Size: $(du -h "$BACKUP_FILE" | cut -f1))"
elif [ -f "./data/chatdb.mv.db" ]; then
    BACKUP_FILE="$BACKUP_DIR/pulsechat_h2_$TIMESTAMP.tar.gz"
    echo "Backing up local H2 database files..."
    tar -czf "$BACKUP_FILE" ./data/chatdb*.db
    echo "Backup completed: $BACKUP_FILE (Size: $(du -h "$BACKUP_FILE" | cut -f1))"
else
    echo "Warning: No running PostgreSQL container or local H2 database found."
    exit 1
fi

# Clean up backups older than RETENTION_DAYS
echo "Pruning backups older than $RETENTION_DAYS days..."
find "$BACKUP_DIR" -type f -name "pulsechat_*" -mtime +"$RETENTION_DAYS" -exec rm -f {} +
echo "=== [PulseChat] Backup process completed successfully ==="
