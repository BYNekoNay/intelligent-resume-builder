#!/usr/bin/env bash
# Read-only preflight for a prospective single-host deployment.
set -uo pipefail

MIN_FREE_GIB="${MIN_FREE_GIB:-20}"
MIN_MEMORY_GIB="${MIN_MEMORY_GIB:-4}"
failed=0

section() { printf '\n== %s ==\n' "$1"; }
ok() { printf '[OK] %s\n' "$1"; }
warn() { printf '[WARN] %s\n' "$1"; }
fail() { printf '[BLOCKER] %s\n' "$1"; failed=1; }

section "Host"
date -Is
id
uname -a
if [[ -r /etc/os-release ]]; then
  . /etc/os-release
  printf 'OS: %s %s\n' "${PRETTY_NAME:-unknown}" "${VERSION_ID:-}"
fi

section "Capacity"
root_free_kib="$(df -Pk / | awk 'NR == 2 { print $4 }')"
root_free_gib=$((root_free_kib / 1024 / 1024))
printf 'Root filesystem free: %s GiB\n' "$root_free_gib"
if (( root_free_gib < MIN_FREE_GIB )); then
  fail "Need at least ${MIN_FREE_GIB} GiB free on / for images, volumes, and releases."
else
  ok "Root filesystem capacity meets the ${MIN_FREE_GIB} GiB threshold."
fi
memory_kib="$(awk '/MemTotal:/ { print $2 }' /proc/meminfo 2>/dev/null || printf '0')"
memory_gib=$((memory_kib / 1024 / 1024))
printf 'Memory: %s GiB\n' "$memory_gib"
if (( memory_gib < MIN_MEMORY_GIB )); then
  fail "Need at least ${MIN_MEMORY_GIB} GiB RAM for MySQL, API, PDF rendering, and the reverse proxy."
else
  ok "Memory meets the ${MIN_MEMORY_GIB} GiB threshold."
fi
printf 'CPU cores: '
nproc 2>/dev/null || printf 'unknown\n'
free -h 2>/dev/null || true

section "Runtime"
if command -v docker >/dev/null 2>&1; then
  ok "Docker is installed."
  docker --version
  if docker compose version >/dev/null 2>&1; then
    ok "Docker Compose v2 is available."
    docker compose version
  else
    fail "Docker Compose v2 is required."
  fi
  if docker info >/dev/null 2>&1; then
    ok "The current user can access the Docker daemon."
  else
    fail "The current user cannot access the Docker daemon."
  fi
else
  fail "Docker is not installed."
fi

section "Network"
if command -v ss >/dev/null 2>&1; then
  ss -ltn '( sport = :80 or sport = :443 )' || true
  if ss -ltn '( sport = :80 or sport = :443 )' | grep -q LISTEN; then
    warn "Port 80 or 443 is already listening; inspect the owning service before deployment."
  else
    ok "Ports 80 and 443 are not currently listening."
  fi
else
  warn "ss is unavailable; inspect ports 80 and 443 manually."
fi
if command -v ufw >/dev/null 2>&1; then
  ufw status || true
elif command -v firewall-cmd >/dev/null 2>&1; then
  firewall-cmd --state 2>/dev/null || true
  firewall-cmd --list-services 2>/dev/null || true
else
  warn "No supported firewall CLI found; verify Alibaba Cloud security-group rules manually."
fi

section "Deployment inputs"
printf '%s\n' "Before deployment, confirm DNS/TLS, a backup destination and retention policy,"
printf '%s\n' "the alert recipient, and production secrets outside the repository."

if (( failed )); then
  printf '\nServer readiness has blockers. No system changes were made.\n' >&2
  exit 1
fi
printf '\nServer readiness passed. No system changes were made.\n'
