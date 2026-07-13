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

Step 6 order: start Doris → `migrate-schema` → start ingest/web. If migration fails, **Web troubleshooting mode** still starts (`ai-apm-web` only; ingest stays down to avoid writing into a broken schema). Web probes Doris once at startup; when unreachable, Doris-backed APIs fail fast so the AI chat page loads quickly for ops expert troubleshooting. Restart web after Doris is fixed to exit troubleshooting mode. Check `GET /health` for `"doris":"UNAVAILABLE"`. Same pattern as `start.sh` when Doris is unavailable.

### One-command recovery (recommended)

If the current `data/` directory is untrusted (for example migration stopped halfway), restore from `backups/` and continue — even when `VERSION` already shows the target release:

```bash
cd /opt/databuff-ai-apm
curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash -s -- --restore-backup
```

Use the latest `backups/data-backup-*.tar.gz` by default. To pick a specific file:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash -s -- \
  --restore-backup=data-backup-20260713-110220.tar.gz
```

Offline / in install dir:

```bash
cd /opt/databuff-ai-apm
./update.sh --restore-backup --version 0.1.3
```

What it does: stop services → restore trusted backup → sync deploy files/images → migrate + start (with auto-retry). It does **not** run `install.sh`.

Requires a pre-upgrade backup under `backups/`. Do not set `SKIP_BACKUP=1` on the original upgrade if you may need this path.

### Manual recovery

If all automatic attempts fail, `data/` is restored to the backup used for recovery and the script exits with an error. You can retry `--restore-backup` again or restore manually:

```bash
cd /opt/databuff-ai-apm
./stop.sh
ls -lt backups/data-backup-*.tar.gz
rm -rf data/
tar -xzf backups/data-backup-YYYYMMDD-HHMMSS.tar.gz -C .
./update.sh --restore-backup --version 0.1.3
```

Do not hand-edit `schema_version` or drop Doris tables unless you know the exact partial state.

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
