#!/bin/bash
set -euo pipefail
umask 077

LATEST=$(ls -1dt /var/backups/vault/data/*/ | head -1)
cd "$LATEST"
sha256sum --quiet -c SHA256SUMS
echo "checksums ok for $LATEST"

mysql -u root -e "DROP DATABASE IF EXISTS vault_restore_check; CREATE DATABASE vault_restore_check"
gunzip -c db.sql.gz | mysql -u root vault_restore_check
for table in app_users stored_files file_permissions shares audit_events magic_link_tokens flyway_schema_history; do
    live=$(mysql -N -u root -e "SELECT COUNT(*) FROM secure_file_storage.$table")
    restored=$(mysql -N -u root -e "SELECT COUNT(*) FROM vault_restore_check.$table")
    echo "$table: $restored rows restored, $live live"
done
mysql -u root -e "DROP DATABASE vault_restore_check"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
tar -xzf files.tgz -C "$WORK"
echo "files: $(find "$WORK/files" -name '*.bin' 2>/dev/null | wc -l) encrypted blobs, $(find "$WORK/.keys" -type f 2>/dev/null | wc -l) keys"
