#!/usr/bin/env bash
# issue-tokens.sh — mint Jira/Confluence/Bitbucket Personal Access Tokens for the local
# Atlassian Data Center rig, and wire them into ~/.zshrc as the JIRA_PAT / CONFLUENCE_PAT /
# BITBUCKET_PAT env vars sdd.yml's atlassian: block (and sdd doctor) expect.
#
# Prerequisites (human steps this script does NOT do — see README.md):
#   - docker compose (in this directory) is up.
#   - All three products have been through their one-time browser setup wizard, with a licence
#     applied and an admin account created.
#
# This script's own job is small and mechanical: PAT creation is itself one REST call per
# product, so it is scripted rather than making a human click "Create token" three times.
#
# Usage:
#   ./issue-tokens.sh [--help]
#
# Reads the admin username/password either from environment variables
# (ATLASSIAN_ADMIN_USER / ATLASSIAN_ADMIN_PASSWORD) or, if unset, prompts for them
# interactively (password entry is hidden). The password is NEVER accepted as a command-line
# argument (that would land it in shell history) and is never handed to curl via -u either
# (that would put it, briefly, in this process's argv, visible to `ps` on a shared machine) —
# see the netrc handling below.
#
# Environment overrides (all optional):
#   JIRA_BASE_URL         default: http://localhost:8080
#   CONFLUENCE_BASE_URL   default: http://localhost:8090
#   BITBUCKET_BASE_URL    default: http://localhost:7990
#   TOKEN_NAME            default: sdd-local-rig
#   TOKEN_EXPIRY_DAYS     default: 30 (best-effort on Bitbucket — see issue_bitbucket_token)
#   ZSHRC                 default: ~/.zshrc — override for testing this script itself.
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"

usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME} [--help]

Mints a Jira PAT, a Confluence PAT, and a Bitbucket access token against the local Atlassian
Data Center rig (see docker-compose.yml in this directory), and appends them to ~/.zshrc as
JIRA_PAT / CONFLUENCE_PAT / BITBUCKET_PAT inside a clearly delimited, idempotent block.

Prerequisites:
  - The three containers are up and each has been through its one-time browser setup wizard
    (licence + admin account). See README.md.

Credentials:
  - Reads ATLASSIAN_ADMIN_USER / ATLASSIAN_ADMIN_PASSWORD from the environment if set;
    otherwise prompts interactively. The password is never accepted as a CLI argument, never
    passed to curl on its command line, and never echoed to the terminal or logged.

Environment overrides:
  JIRA_BASE_URL         default: http://localhost:8080
  CONFLUENCE_BASE_URL   default: http://localhost:8090
  BITBUCKET_BASE_URL    default: http://localhost:7990
  TOKEN_NAME            default: sdd-local-rig
  TOKEN_EXPIRY_DAYS     default: 30
  ZSHRC                 default: ~/.zshrc

After this script finishes, run 'source ~/.zshrc' (or open a new shell) before 'sdd doctor'
will see the new tokens.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

JIRA_BASE_URL="${JIRA_BASE_URL:-http://localhost:8080}"
CONFLUENCE_BASE_URL="${CONFLUENCE_BASE_URL:-http://localhost:8090}"
BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL:-http://localhost:7990}"
TOKEN_NAME="${TOKEN_NAME:-sdd-local-rig}"
TOKEN_EXPIRY_DAYS="${TOKEN_EXPIRY_DAYS:-30}"
ZSHRC="${ZSHRC:-$HOME/.zshrc}"

die() {
    echo "error: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"
}

require_cmd curl
require_cmd python3
require_cmd awk

# --- temp files, cleaned up on every exit path -----------------------------------------------

TMP_FILES=()
cleanup() {
    local f
    for f in "${TMP_FILES[@]:-}"; do
        [[ -n "$f" ]] && rm -f "$f"
    done
}
trap cleanup EXIT

# Usage: new_tmp VARNAME — creates a temp file, chmod 600, and assigns its path to VARNAME via
# printf -v, tracking it in TMP_FILES for cleanup. Deliberately NOT "VAR=$(new_tmp)": that
# command substitution runs this function in a subshell, and "TMP_FILES+=(...)" inside a
# subshell can never mutate the caller's array — caught by testing this script, where it left
# TMP_FILES silently empty at cleanup time.
new_tmp() {
    local f
    f="$(mktemp)"
    chmod 600 "$f"
    TMP_FILES+=("$f")
    printf -v "$1" '%s' "$f"
}

# --- credentials --------------------------------------------------------------------------

ADMIN_USER="${ATLASSIAN_ADMIN_USER:-}"
if [[ -z "${ADMIN_USER}" ]]; then
    read -r -p "Admin username: " ADMIN_USER
fi
[[ -n "${ADMIN_USER}" ]] || die "admin username must not be empty"

ADMIN_PASSWORD="${ATLASSIAN_ADMIN_PASSWORD:-}"
if [[ -z "${ADMIN_PASSWORD}" ]]; then
    read -r -s -p "Admin password (hidden): " ADMIN_PASSWORD
    echo
fi
[[ -n "${ADMIN_PASSWORD}" ]] || die "admin password must not be empty"

host_of() {
    python3 -c 'import sys, urllib.parse as u; print(u.urlparse(sys.argv[1]).hostname or "")' "$1"
}

# A netrc file, not curl's "-u user:pass", so the admin password never appears in this
# process's argv (visible to `ps` on a shared machine, even briefly) the way "-u" would put it
# there. One entry per configured base URL's host — usually all "localhost", but this also
# works if JIRA_BASE_URL/CONFLUENCE_BASE_URL/BITBUCKET_BASE_URL are overridden to distinct
# hosts, since netrc matches by hostname.
new_tmp NETRC
{
    echo "machine $(host_of "${JIRA_BASE_URL}") login ${ADMIN_USER} password ${ADMIN_PASSWORD}"
    echo "machine $(host_of "${CONFLUENCE_BASE_URL}") login ${ADMIN_USER} password ${ADMIN_PASSWORD}"
    echo "machine $(host_of "${BITBUCKET_BASE_URL}") login ${ADMIN_USER} password ${ADMIN_PASSWORD}"
} > "${NETRC}"

# --- masking helper ------------------------------------------------------------------------

# Never print a token in full — a terminal scrollback is a place secrets leak from. Shows only
# the last 4 characters, matching the brief's exact requirement.
mask() {
    local value="$1"
    local len=${#value}
    if (( len <= 4 )); then
        printf '%s' "****"
    else
        printf '****%s' "${value: -4}"
    fi
}

# --- PAT creation ---------------------------------------------------------------------------

# Jira/Confluence Data Center: PUT /rest/pat/latest/tokens with a JSON body naming the token
# and its expiry, authenticated as the admin (the wizard's admin account — there is no token
# yet, that's the point of this call). Response body carries the raw token in "rawToken".
issue_pat() {
    local label="$1" base_url="$2"
    local body response token
    body=$(python3 -c '
import json, sys
name, days = sys.argv[1], int(sys.argv[2])
print(json.dumps({"name": name, "expirationDuration": days}))
' "${TOKEN_NAME}" "${TOKEN_EXPIRY_DAYS}")

    response=$(curl -sS -f \
        --netrc-file "${NETRC}" \
        -H "Content-Type: application/json" \
        -X PUT \
        -d "${body}" \
        "${base_url}/rest/pat/latest/tokens") \
        || die "${label}: PAT creation request failed against ${base_url} — is it up and past the setup wizard?"

    token=$(printf '%s' "${response}" | python3 -c '
import json, sys
data = json.load(sys.stdin)
tok = data.get("rawToken")
if not tok:
    sys.exit("no rawToken in response: " + json.dumps(data))
print(tok)
') || die "${label}: could not parse rawToken from response"

    printf '%s' "${token}"
}

# Bitbucket Data Center: PUT /rest/access-tokens/1.0/users/{user} — a different shape from the
# Jira/Confluence PAT endpoint (brief's exact instruction: different path, different product).
# "expiryDays" is this script's own best guess at Bitbucket's expiry field name (least-certain —
# see README.md's "least-certain API shapes" list); if the live instance rejects it, drop the
# field and mint a token good until revoked. Response carries the raw token in "token".
issue_bitbucket_token() {
    local base_url="$1"
    local body response token
    body=$(python3 -c '
import json, sys
name, days = sys.argv[1], int(sys.argv[2])
print(json.dumps({"name": name, "permissions": ["PROJECT_ADMIN", "REPO_ADMIN"], "expiryDays": days}))
' "${TOKEN_NAME}" "${TOKEN_EXPIRY_DAYS}")

    response=$(curl -sS -f \
        --netrc-file "${NETRC}" \
        -H "Content-Type: application/json" \
        -X PUT \
        -d "${body}" \
        "${base_url}/rest/access-tokens/1.0/users/${ADMIN_USER}") \
        || die "bitbucket: PAT creation request failed against ${base_url} — is it up and past the setup wizard?"

    token=$(printf '%s' "${response}" | python3 -c '
import json, sys
data = json.load(sys.stdin)
tok = data.get("token")
if not tok:
    sys.exit("no token in response: " + json.dumps(data))
print(tok)
') || die "bitbucket: could not parse token from response"

    printf '%s' "${token}"
}

echo "Requesting Jira PAT from ${JIRA_BASE_URL} ..."
JIRA_TOKEN=$(issue_pat "jira" "${JIRA_BASE_URL}")
echo "  got it: $(mask "${JIRA_TOKEN}")"

echo "Requesting Confluence PAT from ${CONFLUENCE_BASE_URL} ..."
CONFLUENCE_TOKEN=$(issue_pat "confluence" "${CONFLUENCE_BASE_URL}")
echo "  got it: $(mask "${CONFLUENCE_TOKEN}")"

echo "Requesting Bitbucket access token from ${BITBUCKET_BASE_URL} ..."
BITBUCKET_TOKEN=$(issue_bitbucket_token "${BITBUCKET_BASE_URL}")
echo "  got it: $(mask "${BITBUCKET_TOKEN}")"

# --- ~/.zshrc surgery ------------------------------------------------------------------------

BEGIN_MARK="# --- sdd: local Atlassian Data Center rig ---"
END_MARK="# --- end sdd ---"

[[ -f "${ZSHRC}" ]] || : > "${ZSHRC}"

BACKUP="${ZSHRC}.bak.$(date +%Y%m%d%H%M%S)"
cp "${ZSHRC}" "${BACKUP}"
echo "Backed up ${ZSHRC} to ${BACKUP}"

# Strip any existing sdd block (idempotent: replace, don't duplicate), preserving everything
# else in the file untouched, including line order.
new_tmp STRIPPED
awk -v begin="${BEGIN_MARK}" -v end="${END_MARK}" '
    $0 == begin { skipping = 1; next }
    $0 == end   { skipping = 0; next }
    skipping    { next }
    { print }
' "${ZSHRC}" > "${STRIPPED}"

# Preserve DEEPSEEK_API_KEY / ROUTER_AI_API_KEY if already set anywhere in the file outside the
# old sdd block; add commented placeholders only when truly absent, matching .env.example's own
# wording so a human sees consistent guidance in either place.
new_tmp NEW_BLOCK
{
    echo "${BEGIN_MARK}"
    echo "export JIRA_PAT=${JIRA_TOKEN}"
    echo "export CONFLUENCE_PAT=${CONFLUENCE_TOKEN}"
    echo "export BITBUCKET_PAT=${BITBUCKET_TOKEN}"
    if ! grep -q '^[^#]*DEEPSEEK_API_KEY=' "${STRIPPED}"; then
        echo "# export DEEPSEEK_API_KEY=sk-REPLACE_ME"
    fi
    if ! grep -q '^[^#]*ROUTER_AI_API_KEY=' "${STRIPPED}"; then
        echo "# export ROUTER_AI_API_KEY=REPLACE_ME"
    fi
    echo "${END_MARK}"
} > "${NEW_BLOCK}"

cat "${STRIPPED}" "${NEW_BLOCK}" > "${ZSHRC}"

echo
echo "Appended to ${ZSHRC}:"
echo "${BEGIN_MARK}"
echo "export JIRA_PAT=$(mask "${JIRA_TOKEN}")"
echo "export CONFLUENCE_PAT=$(mask "${CONFLUENCE_TOKEN}")"
echo "export BITBUCKET_PAT=$(mask "${BITBUCKET_TOKEN}")"
tail -n +5 "${NEW_BLOCK}"
echo
echo "Run 'source ~/.zshrc' (or open a new shell) before 'sdd doctor' will see these tokens."
