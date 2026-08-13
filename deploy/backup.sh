#!/usr/bin/env bash
#
# Nightly logical backup of the CEMS database, implementing the retention policy in
# DATABASE_OPERATIONS.md (7 daily + 4 weekly) for the self-hosted Postgres container.
#
# EBS snapshots protect the volume; this protects against logical damage (a bad migration,
# an accidental delete), which a volume snapshot alone recovers from far more awkwardly.
#
# Install as a cron entry, e.g.:
#   sudo cp deploy/backup.sh /usr/local/bin/cems-backup
#   sudo chmod +x /usr/local/bin/cems-backup
#   sudo crontab -e
#   15 3 * * * /usr/local/bin/cems-backup >> /var/log/cems-backup.log 2>&1

set -euo pipefail

COMPOSE_DIR="${CEMS_COMPOSE_DIR:-/opt/cems/api/api/deploy}"
BACKUP_DIR="${CEMS_BACKUP_DIR:-/var/backups/cems}"
DAILY_KEEP="${CEMS_DAILY_KEEP:-7}"
WEEKLY_KEEP="${CEMS_WEEKLY_KEEP:-4}"

cd "$COMPOSE_DIR"

# Credentials come from the same .env the stack runs with, so the backup can never
# drift out of step with the database it is backing up.
# shellcheck disable=SC1091
set -a && source ./.env && set +a

DB_NAME="${POSTGRES_DB:-cems}"
DB_USER="${POSTGRES_USER:-cems}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly"

DAILY_FILE="$BACKUP_DIR/daily/cems-prod-${DB_NAME}-${TIMESTAMP}.dump"
CONTAINER_FILE="/tmp/cems-backup-${TIMESTAMP}.dump"

# Dump and verify inside the container, then copy out. Doing it this way means the host needs
# no postgres client tools, and the verification runs against a seekable file — pg_restore
# --list cannot reliably read a custom-format archive from a pipe.
#
# Custom format so pg_restore can do selective/parallel restores.
docker compose exec -T db \
    pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --file="$CONTAINER_FILE"

# A pg_dump that dies partway still leaves a file behind. Verify the archive is readable
# before it counts as a backup and rotates an older, good one out of the retention window.
if ! docker compose exec -T db pg_restore --list "$CONTAINER_FILE" > /dev/null 2>&1; then
    docker compose exec -T db rm -f "$CONTAINER_FILE" || true
    echo "ERROR: dump for ${TIMESTAMP} is not a readable archive; aborting without rotating." >&2
    exit 1
fi

docker compose cp "db:$CONTAINER_FILE" "$DAILY_FILE"
docker compose exec -T db rm -f "$CONTAINER_FILE"

chmod 600 "$DAILY_FILE"
echo "Backup written: $DAILY_FILE ($(du -h "$DAILY_FILE" | cut -f1))"

# Promote Sunday's dump to the weekly set.
if [ "$(date -u +%u)" = "7" ]; then
    cp "$DAILY_FILE" "$BACKUP_DIR/weekly/"
fi

# Rotate: sort newest-first by mtime, then delete everything past the retention count.
# Sorting on mtime rather than the filename keeps this correct even if a dump is restored
# or copied in out of order.
prune() {
    local dir="$1" keep="$2"
    find "$dir" -maxdepth 1 -name '*.dump' -printf '%T@ %p\n' \
        | sort -rn | tail -n "+$((keep + 1))" | cut -d' ' -f2- \
        | while read -r old; do
            echo "Pruning $old"
            rm -f "$old"
        done
}

prune "$BACKUP_DIR/daily" "$DAILY_KEEP"
prune "$BACKUP_DIR/weekly" "$WEEKLY_KEEP"
