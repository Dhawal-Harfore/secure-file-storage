#!/bin/bash
set -euo pipefail
umask 077

ROOT=/var/backups/vault/data
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST="$ROOT/$STAMP"
mkdir -p "$DEST"

mysqldump -u root --single-transaction --routines --triggers secure_file_storage | gzip > "$DEST/db.sql.gz"
tar --exclude=./staging --exclude=./work -czf "$DEST/files.tgz" -C /var/lib/vault .
cp /opt/vault/vault-api.jar "$DEST/vault-api.jar"
(cd "$DEST" && sha256sum db.sql.gz files.tgz vault-api.jar > SHA256SUMS)

ls -1dt "$ROOT"/*/ | tail -n +8 | xargs -r rm -rf
echo "backup written to $DEST ($(du -sh "$DEST" | cut -f1))"
