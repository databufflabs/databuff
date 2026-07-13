# Upgrade notes

## Version metadata

| Field | Value |
|-------|-------|
| Schema version | 1 |
| Upgrade type | A (patch — adds `log_dc_record` + `schema_version` ledger) |
| Minimum upgrade from | 0.1.0 |

## Docker

**Retain data (recommended):**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash
# or in install dir:
cd /opt/databuff-ai-apm && ./update.sh
```

**Fresh install (wipes `data/`):**

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

## Kubernetes

In-place upgrade is **not supported** in this release. To change version: uninstall, then run `install.sh` again.

## Schema

- Fresh install: full `sql/databuff.sql`
- Upgrade: incremental scripts under `sql/migrations/`

## Post-upgrade verification

`update.sh` runs `./scripts/verify-upgrade.sh` after services are up (unless `SKIP_VERIFY=1`).

**Automated (recommended):**

```bash
cd /opt/databuff-ai-apm   # or your APM_INSTALL_DIR
./scripts/verify-upgrade.sh
```

Checks typically include: `VERSION` file, `schema_version` in Doris, ingest/web health endpoints, and container readiness.

**Manual spot checks:**

| Check | Command / URL |
|-------|----------------|
| Schema ledger | `docker compose exec -T ai-apm-doris-fe mysql -h127.0.0.1 -P9030 -uroot -N -e "SELECT version FROM databuff.schema_version WHERE id=1"` — expect value from `upgrade-manifest.json` (`schema_version`) |
| Ingest health | `curl -sf http://127.0.0.1:4318/health` |
| Web health | `curl -sf http://127.0.0.1:27403/health` |
| UI smoke | Open `http://<host>:27403`, sign in (`admin` / `Databuff@123`), confirm service list loads |

Optional API regression: from the repo, `deploy/test/run-tests.sh` (requires demo telemetry).

## Staged upgrade (`SKIP_START=1`)

Use when you want to refresh bundle/images first, then start and migrate manually:

```bash
SKIP_START=1 ./update.sh --version 0.1.3
cd /opt/databuff-ai-apm
# shellcheck source=scripts/compose-env.sh
. scripts/compose-env.sh
# shellcheck source=scripts/runtime.sh
. scripts/runtime.sh
ensure_vm_max_map_count
prepare_compose_start
compose_up_wait ai-apm-doris-fe ai-apm-doris-be
./scripts/migrate-schema.sh
compose_up ai-apm-ingest ai-apm-web
./scripts/verify-upgrade.sh
```

Set `SKIP_VERIFY=1` on `update.sh` if you will verify only after the manual start/migrate steps above.

## Schema migration failure

Step 6 (start Doris, `migrate-schema`, start ingest/web, verify) is retried automatically up to **3** times (`UPDATE_MAX_ATTEMPTS`, default 3). On each retry, `update.sh` stops containers, restores the `data/` backup from step 3/6, and runs step 6 again. Deploy files and images from steps 4–5 are not re-downloaded.

Requires a pre-upgrade backup: do **not** set `SKIP_BACKUP=1` if you want auto-recovery.

```bash
# optional: change retry count (default 3)
UPDATE_MAX_ATTEMPTS=3 ./update.sh --version 0.1.3
```

If all attempts fail, `data/` is restored to the pre-upgrade backup and the script exits with an error. Manual recovery:

```bash
cd /opt/databuff-ai-apm
./stop.sh
ls -lt backups/data-backup-*.tar.gz
rm -rf data/
tar -xzf backups/data-backup-YYYYMMDD-HHMMSS.tar.gz -C .
./update.sh --version 0.1.3
```

Do not hand-edit `schema_version` or drop Doris tables unless you know the exact partial state. Use `update.sh`, not `install.sh`, to retry after restore.

## Rollback

Each upgrade creates `backups/data-backup-<timestamp>.tar.gz` under the install directory (unless `SKIP_BACKUP=1`).

**Data rollback (restore pre-upgrade `data/`):**

```bash
cd /opt/databuff-ai-apm
./stop.sh
ls -lt backups/data-backup-*.tar.gz   # pick the backup from before the failed upgrade
rm -rf data/
tar -xzf backups/data-backup-YYYYMMDD-HHMMSS.tar.gz -C .
```

To run the **previous application version** with restored data, re-apply that release bundle (offline `UPDATE_BUNDLE_ROOT` or `update.sh --version <old>`), then `./start.sh` and `./scripts/verify-upgrade.sh`.

`install.sh` wipes the install directory including `data/` — do **not** use it for rollback.
