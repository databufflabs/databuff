#!/usr/bin/env bash
# Shared Doris 4.x BE readiness helpers (Docker init-doris + K8s lib.sh).
# Caller must define doris_mysql() before calling these functions.

capacity_value_usable() {
  local val="${1:-}"
  val="$(echo "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  [ -z "$val" ] && return 1
  capacity_value_stub "$val" && return 1
  case "$val" in
    0 | 0.000 | "0.000 B" | "0 B" | "0.000 KB" | "0.000 MB" | "0.000 GB" | "0.000 TB")
      return 1
      ;;
  esac
  return 0
}

# Doris BE may report AvailCapacity=1.000 B before disks are registered.
capacity_value_stub() {
  local val="${1:-}"
  val="$(echo "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  case "$val" in
    "1.000 B" | "1 B")
      return 0
      ;;
  esac
  return 1
}

# Doris 4.1 SHOW BACKENDS 返回 tab 分隔表格（非 \G 竖排；\G 行首有空格，勿用 /^AvailCapacity:/）
parse_show_backends() {
  doris_mysql -e 'SHOW BACKENDS' 2>/dev/null | awk -F'\t' '
    NR == 1 {
      for (i = 1; i <= NF; i++) {
        if ($i == "Alive") alive_col = i
        if ($i == "AvailCapacity") avail_col = i
        if ($i == "TotalCapacity") total_col = i
      }
      next
    }
    alive_col > 0 && $(alive_col) == "true" {
      alive++
      if (cap == "" && avail_col > 0 && $(avail_col) != "") {
        cap = $(avail_col)
        gsub(/^[ \t]+|[ \t]+$/, "", cap)
      }
      if (cap == "" && total_col > 0 && $(total_col) != "") {
        cap = $(total_col)
        gsub(/^[ \t]+|[ \t]+$/, "", cap)
      }
    }
    END {
      print alive + 0 "\t" cap
    }
  '
}

doris_be_ready_status() {
  local parsed alive avail
  parsed="$(parse_show_backends)" || return 1
  alive="${parsed%%$'\t'*}"
  avail="${parsed#*$'\t'}"
  [ "$avail" = "$parsed" ] && avail=""

  if [ "${alive:-0}" -ge 1 ] && capacity_value_usable "$avail"; then
    printf '%s\t%s\n' "$alive" "$avail"
    return 0
  fi
  printf '%s\t%s\n' "${alive:-0}" "${avail:-}"
  return 1
}

doris_be_alive_status() {
  local parsed alive
  parsed="$(parse_show_backends)" || return 1
  alive="${parsed%%$'\t'*}"
  if [ "${alive:-0}" -ge 1 ]; then
    printf '%s\n' "$alive"
    return 0
  fi
  printf '%s\n' "${alive:-0}"
  return 1
}

# Wait until at least one BE is alive (ignores AvailCapacity stub values).
wait_for_be_alive() {
  local log_prefix="${1:-[init-doris]}"
  local max_attempts="${2:-120}"
  local sleep_sec="${3:-3}"
  local max_wait_sec=$((max_attempts * sleep_sec))
  local elapsed=0 alive=0

  echo "${log_prefix} waiting for Doris BE alive (timeout ${max_wait_sec}s)"
  while [ "$elapsed" -lt "$max_wait_sec" ]; do
    if doris_be_alive_status >/tmp/apm-init-doris-be-alive.$$ 2>/dev/null; then
      alive="$(cat /tmp/apm-init-doris-be-alive.$$)"
      rm -f /tmp/apm-init-doris-be-alive.$$
      echo "${log_prefix} BE alive (alive=${alive}, waited ${elapsed}s)"
      return 0
    fi
    if [ -f /tmp/apm-init-doris-be-alive.$$ ]; then
      alive="$(cat /tmp/apm-init-doris-be-alive.$$ 2>/dev/null)" || alive=0
      rm -f /tmp/apm-init-doris-be-alive.$$
    fi
    if [ "$elapsed" -eq 0 ] || [ $((elapsed % 30)) -eq 0 ]; then
      echo "${log_prefix} BE not alive yet (alive=${alive:-0}, ${elapsed}s/${max_wait_sec}s)"
    fi
    sleep "$sleep_sec"
    elapsed=$((elapsed + sleep_sec))
  done

  echo "${log_prefix} timeout waiting for Doris BE alive (${max_wait_sec}s)" >&2
  doris_mysql -e 'SHOW BACKENDS' >&2 || true
  return 1
}

# Wait until AvailCapacity is no longer the BE startup stub (default: 2 min, 10s interval).
wait_for_be_avail_stable() {
  local log_prefix="${1:-[init-doris]}"
  local max_wait_sec="${2:-120}"
  local sleep_sec=10
  local elapsed=0
  local parsed alive avail reason

  echo "${log_prefix} waiting for BE disk capacity (timeout ${max_wait_sec}s)"
  while [ "$elapsed" -lt "$max_wait_sec" ]; do
    parsed="$(parse_show_backends 2>/dev/null)" || parsed=$'\t'
    alive="${parsed%%$'\t'*}"
    avail="${parsed#*$'\t'}"
    [ "$avail" = "$parsed" ] && avail=""

    if ! capacity_value_stub "$avail"; then
      echo "${log_prefix} BE disk capacity ok (alive=${alive:-0}, avail=${avail:-n/a}, waited ${elapsed}s)"
      return 0
    fi

    reason="AvailCapacity=${avail:-n/a}, disks not registered yet"
    echo "${log_prefix} sleep ${sleep_sec}s: ${reason} (${elapsed}s/${max_wait_sec}s)"
    sleep "$sleep_sec"
    elapsed=$((elapsed + sleep_sec))
  done

  echo "${log_prefix} timeout after ${max_wait_sec}s waiting for real BE capacity (avail=${avail:-n/a})" >&2
  doris_mysql -e 'SHOW BACKENDS' >&2 || true
  return 1
}

# Transient Doris DDL errors when BE disks/replication are not fully registered yet.
# Do not treat all ERROR 1105 as retryable ("already exists" is 1105 too).
doris_sql_retryable_error() {
  local err="$1"
  case "$err" in
    *"Failed to find enough backend"*) return 0 ;;
    *"Create failed replications"*) return 0 ;;
    *"hdd disks count={}"*) return 0 ;;
    *"already exists"*) return 1 ;;
    *"Duplicate"*) return 1 ;;
  esac
  return 1
}

# Apply SQL file via doris_mysql stdin; retry up to 4 times (5s apart) on transient BE errors.
apply_doris_sql_file() {
  local sql_file="$1"
  local max_attempts="${2:-4}"
  local attempt=1
  local err=""

  while [ "$attempt" -le "$max_attempts" ]; do
    err="$(doris_mysql <"$sql_file" 2>&1)" && return 0

    if [ "$attempt" -lt "$max_attempts" ] && doris_sql_retryable_error "$err"; then
      sleep 5
      attempt=$((attempt + 1))
      continue
    fi

    echo "$err" >&2
    return 1
  done
}
