#!/usr/bin/env bash
# mirror.sh — copy the estate from GitHub into the local Bitbucket Data Center rig, and
# re-point each checkout's `origin` remote at Bitbucket.
#
# Discovers "the estate" exactly the way `sdd` itself does (see
# sdd-index/src/main/java/sdd/index/scan/WorkspaceScanner.java): every directory directly
# under the given workspace that contains a `.git` entry, sorted by name. Nothing here is
# hard-coded to a repo count or a repo list — whatever the workspace holds is what gets
# mirrored, matching what `sdd index` would scan.
#
# For each discovered repo:
#   1. Ensure the TRADING (or --project) Bitbucket project exists (reused if already there).
#   2. Ensure a same-named Bitbucket repo exists under that project (reused if already there).
#   3. `git clone --mirror` from the repo's GitHub origin URL into a throwaway temp dir, then
#      `git push --mirror` that clone into Bitbucket. Mirroring from the remote (not the local
#      checkout) carries every branch and tag, not just whatever happens to be checked out.
#   4. Re-point the checkout's own `origin` at Bitbucket, preserving the original GitHub URL as
#      a `github` remote, and verify the new origin resolves with `git ls-remote`.
#
# This is destructive to real checkouts' remotes (though never to their commit history — commits
# are mirrored, not deleted anywhere). It prints the full plan and asks for confirmation before
# the first write, unless run with --yes.
#
# Usage:
#   ./mirror.sh <workspace-dir> [--yes] [--project KEY] [--bitbucket-base-url URL] [--help]
#
# Requires BITBUCKET_API_KEY in the environment (see issue-tokens.sh) — an access token with
# PROJECT_ADMIN/REPO_ADMIN permission on the target project, or enough to create one.
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"

usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME} <workspace-dir> [options]

Mirrors every git checkout directly under <workspace-dir> (the same discovery rule
sdd-index/.../WorkspaceScanner.java uses) into the local Bitbucket Data Center rig, and
re-points each checkout's 'origin' remote at Bitbucket — preserving the original GitHub
remote as 'github'.

Options:
  --yes                    Skip the confirmation prompt (needed for non-interactive runs).
  --project KEY            Bitbucket project key to create/reuse (default: TRADING).
  --bitbucket-base-url URL Bitbucket base URL (default: http://localhost:7990).
  --help                   Show this help and exit.

Requires BITBUCKET_API_KEY in the environment. Never pass a token on the command line.

Re-runnable: an existing Bitbucket project or repo is reused, not recreated; a checkout
already re-pointed at Bitbucket is detected (via its 'github' remote) and only re-mirrored,
not re-pointed a second time incorrectly.
EOF
}

WORKSPACE=""
YES=0
PROJECT="TRADING"
BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL:-http://localhost:7990}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --yes) YES=1; shift ;;
        --project) PROJECT="$2"; shift 2 ;;
        --bitbucket-base-url) BITBUCKET_BASE_URL="$2"; shift 2 ;;
        -*) echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
        *)
            if [[ -n "${WORKSPACE}" ]]; then
                echo "error: unexpected extra argument: $1" >&2
                exit 2
            fi
            WORKSPACE="$1"
            shift
            ;;
    esac
done

die() {
    echo "error: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"
}

require_cmd curl
require_cmd git
require_cmd python3

[[ -n "${WORKSPACE}" ]] || { usage >&2; die "workspace directory argument is required"; }
[[ -d "${WORKSPACE}" ]] || die "workspace directory does not exist: ${WORKSPACE}"
: "${BITBUCKET_API_KEY:?BITBUCKET_API_KEY must be set — see scripts/atlassian-dc/issue-tokens.sh}"

BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL%/}"

# --- temp files/dirs, cleaned up on every exit path (including failure) ----------------------

TMP_FILES=()
TMP_DIRS=()
cleanup() {
    local f d
    for f in "${TMP_FILES[@]:-}"; do
        [[ -n "$f" ]] && rm -f "$f"
    done
    for d in "${TMP_DIRS[@]:-}"; do
        [[ -n "$d" ]] && rm -rf "$d"
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

MIRROR_ROOT="$(mktemp -d)"
TMP_DIRS+=("${MIRROR_ROOT}")

# curl's Authorization header via a config file (-K), not "-H ... $TOKEN" on curl's own command
# line — keeps the PAT out of this process's argv (visible to `ps`, even briefly).
new_tmp BEARER_CONFIG
echo "header = \"Authorization: Bearer ${BITBUCKET_API_KEY}\"" > "${BEARER_CONFIG}"

bb_curl() {
    curl -sS -K "${BEARER_CONFIG}" "$@"
}

# git push/ls-remote auth via GIT_ASKPASS, not an embedded "https://user:TOKEN@host/..." URL —
# same reasoning: an embedded-credential URL would put the PAT in every git subprocess's argv.
new_tmp ASKPASS
chmod 700 "${ASKPASS}"
cat > "${ASKPASS}" <<'ASKPASS_EOF'
#!/usr/bin/env bash
case "$1" in
    Username*) printf '%s' "x-token-auth" ;;
    Password*) printf '%s' "${BITBUCKET_API_KEY}" ;;
esac
ASKPASS_EOF

git_authenticated() {
    GIT_ASKPASS="${ASKPASS}" GIT_TERMINAL_PROMPT=0 BITBUCKET_API_KEY="${BITBUCKET_API_KEY}" git "$@"
}

# --- Bitbucket slug/URL conventions, mirroring RemoteGit.java exactly ------------------------
# (sdd-cli/src/main/java/sdd/cli/review/RemoteGit.java: cloneUrl/repoSlug). Project is
# lowercased in the SCM clone-URL path segment only, never in REST {projectKey} paths — see
# that class's javadoc for why the two are deliberately NOT the same casing convention.

repo_slug() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]/-/g'
}

clone_url() {
    local repo_dirname="$1"
    local project_lower
    project_lower="$(printf '%s' "${PROJECT}" | tr '[:upper:]' '[:lower:]')"
    printf '%s/scm/%s/%s.git' "${BITBUCKET_BASE_URL}" "${project_lower}" "$(repo_slug "${repo_dirname}")"
}

# Strips any embedded "user[:pass]@" credential from a URL before it is PRINTED — never used on
# the URL actually handed to git. A remote can carry embedded credentials in more ways than this
# script controls (e.g. a corporate git config's "url.<credentialed>.insteadOf" rewrite rule,
# observed in exactly this shape while testing this script), so the plan/summary output redacts
# on principle rather than assuming origin URLs are always credential-free.
redact_url() {
    printf '%s' "$1" | sed -E 's#(https?://)[^/@[:space:]]*@#\1#'
}

# --- estate discovery, matching WorkspaceScanner.scan exactly --------------------------------
# Every directory directly under the workspace that has a .git entry (file OR directory —
# WorkspaceScanner checks Files.exists, not isDirectory, since a worktree checkout's .git is a
# file), sorted by name. No excludes list is applied here (unlike sdd.yml's `excludes:`) — this
# script mirrors the raw estate; if a directory should never go to Bitbucket, do not put it
# under the workspace this script is pointed at.

REPOS=()
while IFS= read -r dir; do
    REPOS+=("$dir")
done < <(find "${WORKSPACE}" -mindepth 1 -maxdepth 1 -type d ! -name '.*' -print | sort)

DISCOVERED=()
for dir in "${REPOS[@]:-}"; do
    [[ -n "$dir" ]] || continue
    if [[ -e "${dir}/.git" ]]; then
        DISCOVERED+=("$dir")
    fi
done

[[ ${#DISCOVERED[@]} -gt 0 ]] || die "no git checkouts found directly under ${WORKSPACE}"

# --- read each repo's current GitHub origin (the URL to mirror FROM) -------------------------
# A repo already re-pointed at Bitbucket by a previous run of this script keeps its original
# GitHub URL on a 'github' remote (see the re-point step below) — read from there when present,
# so re-running this script is idempotent even after origin has already changed.

github_origin_of() {
    local dir="$1"
    if git -C "${dir}" remote get-url github >/dev/null 2>&1; then
        git -C "${dir}" remote get-url github
    else
        git -C "${dir}" remote get-url origin
    fi
}

# --- print the plan and confirm --------------------------------------------------------------

echo "Bitbucket base URL: ${BITBUCKET_BASE_URL}"
echo "Bitbucket project:  ${PROJECT} (created if it does not already exist)"
echo "Workspace:           ${WORKSPACE}"
echo
echo "Discovered ${#DISCOVERED[@]} repo(s):"
printf '%-28s %-55s %s\n' "REPO" "GITHUB ORIGIN" "BITBUCKET TARGET"
for dir in "${DISCOVERED[@]}"; do
    name="$(basename "${dir}")"
    origin="$(github_origin_of "${dir}")"
    printf '%-28s %-55s %s\n' "${name}" "$(redact_url "${origin}")" "$(clone_url "${name}")"
done
echo
echo "This will, per repo above:"
echo "  - create (or reuse) the Bitbucket repo"
echo "  - clone --mirror from the GitHub origin URL and push --mirror it into Bitbucket"
echo "  - REWRITE the checkout's 'origin' remote to point at Bitbucket"
echo "  - preserve the current GitHub URL as a 'github' remote"
echo

if [[ "${YES}" -ne 1 ]]; then
    read -r -p "Proceed? [y/N] " confirm
    case "${confirm}" in
        y|Y|yes|YES) ;;
        *) echo "Aborted — no changes made." ; exit 1 ;;
    esac
fi

# --- 1. create/reuse the Bitbucket project ----------------------------------------------------

ensure_project() {
    local status
    status="$(bb_curl -o /dev/null -w '%{http_code}' "${BITBUCKET_BASE_URL}/rest/api/1.0/projects/${PROJECT}")"
    if [[ "${status}" == "200" ]]; then
        echo "project ${PROJECT}: already exists, reusing"
        return
    fi
    echo "project ${PROJECT}: creating"
    local body
    body=$(python3 -c 'import json,sys; print(json.dumps({"key": sys.argv[1], "name": sys.argv[1]}))' "${PROJECT}")
    bb_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
        "${BITBUCKET_BASE_URL}/rest/api/1.0/projects" >/dev/null \
        || die "could not create Bitbucket project ${PROJECT}"
}

ensure_project

# --- 2. create/reuse each Bitbucket repo -------------------------------------------------------

ensure_repo() {
    local name="$1" slug status
    slug="$(repo_slug "${name}")"
    status="$(bb_curl -o /dev/null -w '%{http_code}' \
        "${BITBUCKET_BASE_URL}/rest/api/1.0/projects/${PROJECT}/repos/${slug}")"
    if [[ "${status}" == "200" ]]; then
        echo "  repo ${name}: already exists (${slug}), reusing"
        return
    fi
    echo "  repo ${name}: creating (${slug})"
    local body
    body=$(python3 -c 'import json,sys; print(json.dumps({"name": sys.argv[1], "scmId": "git"}))' "${name}")
    bb_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
        "${BITBUCKET_BASE_URL}/rest/api/1.0/projects/${PROJECT}/repos" >/dev/null \
        || die "could not create Bitbucket repo ${name}"
}

# --- 3. mirror clone -> mirror push -------------------------------------------------------------

mirror_repo() {
    local name="$1" origin_url="$2" target_url="$3"
    local clone_dir="${MIRROR_ROOT}/${name}.git"
    echo "  cloning --mirror from $(redact_url "${origin_url}") ..."
    git clone --mirror --quiet "${origin_url}" "${clone_dir}" \
        || die "could not mirror-clone ${name} from $(redact_url "${origin_url}")"
    echo "  pushing --mirror to ${target_url} ..."
    git_authenticated -C "${clone_dir}" push --mirror --quiet "${target_url}" \
        || die "could not mirror-push ${name} to ${target_url}"

    local branches tags
    branches="$(git -C "${clone_dir}" for-each-ref --format='%(refname)' refs/heads | wc -l | tr -d ' ')"
    tags="$(git -C "${clone_dir}" for-each-ref --format='%(refname)' refs/tags | wc -l | tr -d ' ')"
    rm -rf "${clone_dir}"
    printf '%s %s' "${branches}" "${tags}"
}

# --- 4. re-point the checkout's origin, preserving github, and verify -------------------------

repoint_origin() {
    local dir="$1" github_url="$2" target_url="$3"
    if ! git -C "${dir}" remote get-url github >/dev/null 2>&1; then
        git -C "${dir}" remote add github "${github_url}"
    fi
    if git -C "${dir}" remote get-url origin >/dev/null 2>&1; then
        git -C "${dir}" remote set-url origin "${target_url}"
    else
        git -C "${dir}" remote add origin "${target_url}"
    fi
    git_authenticated -C "${dir}" ls-remote origin >/dev/null \
        || die "re-pointed origin for ${dir} but 'git ls-remote origin' failed against ${target_url}"
}

# --- run it all, collecting the summary table ---------------------------------------------------

new_tmp SUMMARY_FILE
: > "${SUMMARY_FILE}"

for dir in "${DISCOVERED[@]}"; do
    name="$(basename "${dir}")"
    echo
    echo "=== ${name} ==="
    ensure_repo "${name}"
    origin_url="$(github_origin_of "${dir}")"
    target_url="$(clone_url "${name}")"
    counts="$(mirror_repo "${name}" "${origin_url}" "${target_url}")"
    branches="${counts% *}"
    tags="${counts#* }"
    repoint_origin "${dir}" "${origin_url}" "${target_url}"
    echo "${name}|${target_url}|${branches}|${tags}" >> "${SUMMARY_FILE}"
done

echo
echo "=== Summary ==="
printf '%-28s %-45s %-9s %s\n' "REPO" "BITBUCKET URL" "BRANCHES" "TAGS"
while IFS='|' read -r name target_url branches tags; do
    printf '%-28s %-45s %-9s %s\n' "${name}" "${target_url}" "${branches}" "${tags}"
done < "${SUMMARY_FILE}"
