#!/usr/bin/env bash
# seed.sh — create the fixture data the sdd Atlassian end-to-end run needs, REST-only against
# the local Data Center rig, and dump the raw JSON responses as the new test fixtures.
#
# What this creates:
#   - A Confluence space and a page holding REAL spec prose about one of the mirrored repos
#     (default: order-service's /healthz endpoint — the same scenario docs/README.md's own
#     Quickstart walks through, so a human reading both sees the same story).
#   - A Jira project and an issue (PROJ-1) whose description links that page as a NAMED
#     HYPERLINK ("[the spec|http://...]" wiki markup, which Jira Data Center renders to
#     <a href="...">the spec</a>) — the exact case Task 3's fix
#     (sdd-plan/src/main/java/sdd/plan/jira/JiraClient.java#hrefsIn) exists for. The same page
#     is ALSO attached as a Jira remote link, so both discovery channels
#     (LinkHarvester's href harvest AND its remote-link harvest) are exercised by one issue.
#   - A subtask (PROJ-2) and an "is blocked by" linked issue (PROJ-3) on PROJ-1.
#   - A clarifying comment on PROJ-1.
#
# Then it re-fetches everything through the EXACT REST calls sdd's own Java clients make
# (JiraClient.fetchIssue, ConfluenceClient.fetchPage, BitbucketClient's default-branch/project
# lookups) and writes the raw JSON responses into the test fixture directories, replacing the
# hand-written guesses from Tasks 3 and 5 with recordings of the real thing. It prints exactly
# which files were written (and which hand-written fixtures were deliberately left untouched,
# because this seed does not model every scenario they cover) so a human can diff old vs new.
#
# Prerequisites:
#   - The rig is up, all three setup wizards are done, and scripts/atlassian-dc/issue-tokens.sh
#     has minted JIRA_PAT / CONFLUENCE_PAT / BITBUCKET_PAT into the environment.
#   - scripts/bitbucket-dc/mirror.sh has already mirrored the estate — this script's Bitbucket
#     fixture dump reads an already-mirrored repo (--repo, default order-service) rather than
#     creating one itself.
#
# Usage:
#   ./seed.sh [--repo NAME] [--jira-project KEY] [--confluence-space KEY]
#             [--bitbucket-project KEY] [--help]
#
# Re-running: the Confluence space/page and Jira project are reused if already present (REST
# has a natural existence check for each). Jira ISSUES are not — Jira has no natural
# "does this issue already exist" check, so re-running this script creates a fresh PROJ-1/2/3
# each time. This is disclosed, not silent — see README.md.
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME} [options]

Seeds the local Atlassian Data Center rig with fixture data for the sdd end-to-end run, and
dumps the raw REST responses into sdd-plan/src/test/resources/{jira,confluence}/ and
sdd-cli/src/test/resources/bitbucket/, replacing the hand-written Task 3/5 fixtures.

Options:
  --repo NAME               Mirrored repo the seeded spec is about, and whose Bitbucket
                             fixtures are dumped (default: order-service). Must already exist
                             in Bitbucket — run mirror.sh first.
  --jira-project KEY        Jira project key to create/reuse (default: PROJ).
  --confluence-space KEY    Confluence space key to create/reuse (default: ENG).
  --bitbucket-project KEY   Bitbucket project key to read from (default: TRADING) — must match
                             what mirror.sh used.
  --help                    Show this help and exit.

Requires JIRA_PAT, CONFLUENCE_PAT, BITBUCKET_PAT in the environment (see issue-tokens.sh).
Never pass a token on the command line.

Environment overrides:
  JIRA_BASE_URL         default: http://localhost:8080
  CONFLUENCE_BASE_URL   default: http://localhost:8090
  BITBUCKET_BASE_URL    default: http://localhost:7990
EOF
}

REPO_NAME="order-service"
JIRA_PROJECT="PROJ"
CONFLUENCE_SPACE="ENG"
BITBUCKET_PROJECT="TRADING"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --repo) REPO_NAME="$2"; shift 2 ;;
        --jira-project) JIRA_PROJECT="$2"; shift 2 ;;
        --confluence-space) CONFLUENCE_SPACE="$2"; shift 2 ;;
        --bitbucket-project) BITBUCKET_PROJECT="$2"; shift 2 ;;
        -*) echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
        *) echo "error: unexpected extra argument: $1" >&2; usage >&2; exit 2 ;;
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
require_cmd python3

: "${JIRA_PAT:?JIRA_PAT must be set — see scripts/atlassian-dc/issue-tokens.sh}"
: "${CONFLUENCE_PAT:?CONFLUENCE_PAT must be set — see scripts/atlassian-dc/issue-tokens.sh}"
: "${BITBUCKET_PAT:?BITBUCKET_PAT must be set — see scripts/atlassian-dc/issue-tokens.sh}"

JIRA_BASE_URL="${JIRA_BASE_URL:-http://localhost:8080}"
CONFLUENCE_BASE_URL="${CONFLUENCE_BASE_URL:-http://localhost:8090}"
BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL:-http://localhost:7990}"
JIRA_BASE_URL="${JIRA_BASE_URL%/}"
CONFLUENCE_BASE_URL="${CONFLUENCE_BASE_URL%/}"
BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL%/}"

# --- temp files, cleaned up on every exit path ------------------------------------------------

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

# Each site's Authorization header goes in its own curl config file (-K), never on curl's own
# command line — keeps every PAT out of this process's argv (visible to `ps`, even briefly).
new_tmp JIRA_CONFIG
echo "header = \"Authorization: Bearer ${JIRA_PAT}\"" > "${JIRA_CONFIG}"
new_tmp CONFLUENCE_CONFIG
echo "header = \"Authorization: Bearer ${CONFLUENCE_PAT}\"" > "${CONFLUENCE_CONFIG}"
new_tmp BITBUCKET_CONFIG
echo "header = \"Authorization: Bearer ${BITBUCKET_PAT}\"" > "${BITBUCKET_CONFIG}"

jira_curl() { curl -sS -K "${JIRA_CONFIG}" "$@"; }
confluence_curl() { curl -sS -K "${CONFLUENCE_CONFIG}" "$@"; }
bb_curl() { curl -sS -K "${BITBUCKET_CONFIG}" "$@"; }

json_get() {
    # json_get '<dotted.path>' (json on stdin) — a tiny dotted-path reader so this script
    # does not hand-roll JSON string parsing with grep/sed for anything that matters (issue
    # keys, page ids — values later REST calls depend on).
    python3 -c '
import json, sys
data = json.load(sys.stdin)
path = sys.argv[1].split(".")
for key in path:
    if isinstance(data, list):
        data = data[int(key)]
    else:
        data = data.get(key)
    if data is None:
        sys.exit("path not found: " + sys.argv[1])
print(data)
' "$1"
}

FIXTURES_WRITTEN=()
FIXTURES_SKIPPED=(
    "sdd-plan/src/test/resources/jira/issue-with-subtasks-and-links.json"
    "sdd-plan/src/test/resources/jira/issue-remote-link-only.json"
    "sdd-plan/src/test/resources/jira/remotelink-empty.json"
    "sdd-plan/src/test/resources/jira/issue-with-confluence-link.json"
    "sdd-plan/src/test/resources/confluence/content-search-empty.json"
    "sdd-plan/src/test/resources/confluence/content-search-by-title.json"
)

write_fixture() {
    local rel_path="$1" body="$2"
    local abs_path="${REPO_ROOT}/${rel_path}"
    mkdir -p "$(dirname "${abs_path}")"
    # Reformats via an explicit json.dump, not "python3 -m json.tool" — that CLI tool colorizes
    # its output by default on recent Python (observed on 3.14, even redirected to a file, not
    # just a tty), which would corrupt these fixtures with embedded ANSI escape codes.
    printf '%s\n' "${body}" | python3 -c '
import json, sys
data = json.load(sys.stdin)
json.dump(data, sys.stdout, indent=2, sort_keys=False)
sys.stdout.write("\n")
' > "${abs_path}" \
        || die "response for ${rel_path} was not valid JSON: ${body}"
    FIXTURES_WRITTEN+=("${rel_path}")
    echo "  wrote ${rel_path}"
}

# --- 1. Confluence space (reuse if present) ----------------------------------------------------

echo "=== Confluence space ${CONFLUENCE_SPACE} ==="
space_status="$(confluence_curl -o /dev/null -w '%{http_code}' "${CONFLUENCE_BASE_URL}/rest/api/space/${CONFLUENCE_SPACE}")"
if [[ "${space_status}" == "200" ]]; then
    echo "  already exists, reusing"
else
    echo "  creating"
    body=$(python3 -c '
import json, sys
key = sys.argv[1]
print(json.dumps({
    "key": key,
    "name": "Engineering",
    "description": {"plain": {"value": "Spec pages for the sdd Atlassian rig", "representation": "plain"}},
}))
' "${CONFLUENCE_SPACE}")
    confluence_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
        "${CONFLUENCE_BASE_URL}/rest/api/space" >/dev/null \
        || die "could not create Confluence space ${CONFLUENCE_SPACE}"
fi

# --- 2. Confluence page: real spec prose about --repo ------------------------------------------

PAGE_TITLE="${REPO_NAME} — Health Endpoint Spec"
echo "=== Confluence page \"${PAGE_TITLE}\" ==="
search_response="$(confluence_curl -f \
    "${CONFLUENCE_BASE_URL}/rest/api/content?spaceKey=${CONFLUENCE_SPACE}&title=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "${PAGE_TITLE}")&expand=version")" \
    || die "could not search Confluence for an existing page titled \"${PAGE_TITLE}\""
existing_count="$(printf '%s' "${search_response}" | json_get size 2>/dev/null || echo 0)"

if [[ "${existing_count}" != "0" ]]; then
    PAGE_ID="$(printf '%s' "${search_response}" | json_get results.0.id)"
    echo "  already exists (id ${PAGE_ID}), reusing"
else
    page_html=$(cat <<HTML
<h1>${REPO_NAME} — Health Endpoint</h1>
<p>${REPO_NAME} currently has no way for a load balancer or an orchestrator to ask whether the
process is ready to receive traffic. This page is the detailed spec behind ${JIRA_PROJECT}-1.</p>
<h2>Requirements</h2>
<ul>
<li>GET /healthz returns 200 with a small JSON body ({"status":"ok"}) once the service has
finished startup and its dependencies (database, downstream order-events topic) are reachable.</li>
<li>GET /healthz returns 503 while any dependency check is failing, with a body naming which
check failed.</li>
<li>The endpoint must not require authentication — a load balancer polling it has no credential.</li>
</ul>
<h2>Acceptance criteria</h2>
<ul>
<li>A fresh checkout of ${REPO_NAME} responds 200 from /healthz within 30 seconds of startup.</li>
<li>Stopping the database (or blocking the events topic) flips /healthz to 503 within one poll
interval.</li>
</ul>
HTML
)
    body=$(python3 -c '
import json, sys
title, space, html = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "type": "page",
    "title": title,
    "space": {"key": space},
    "body": {"storage": {"value": html, "representation": "storage"}},
}))
' "${PAGE_TITLE}" "${CONFLUENCE_SPACE}" "${page_html}")
    create_response="$(confluence_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
        "${CONFLUENCE_BASE_URL}/rest/api/content")" \
        || die "could not create Confluence page \"${PAGE_TITLE}\""
    PAGE_ID="$(printf '%s' "${create_response}" | json_get id)"
    echo "  created (id ${PAGE_ID})"
fi

# The canonical URL form ConfluenceClient itself renders for every fetched page
# (sdd-plan/src/main/java/sdd/plan/confluence/ConfluenceClient.java#canonicalUrl) — using the
# same shape here means the Jira href/remote-link this script creates below resolves through
# exactly the same code path a human's own pasted link would.
PAGE_URL="${CONFLUENCE_BASE_URL}/pages/viewpage.action?pageId=${PAGE_ID}"

# --- 3. Jira project (reuse if present) ---------------------------------------------------------

echo "=== Jira project ${JIRA_PROJECT} ==="
project_status="$(jira_curl -o /dev/null -w '%{http_code}' "${JIRA_BASE_URL}/rest/api/2/project/${JIRA_PROJECT}")"
if [[ "${project_status}" == "200" ]]; then
    echo "  already exists, reusing"
else
    echo "  creating"
    # Least-certain shape in this script — see README.md's "least-certain API shapes" list:
    # Jira Server/DC project creation needs a lead username, read here from the PAT's own
    # identity (GET /rest/api/2/myself) rather than asking for yet another credential.
    myself="$(jira_curl -f "${JIRA_BASE_URL}/rest/api/2/myself")" || die "could not read /rest/api/2/myself"
    lead="$(printf '%s' "${myself}" | json_get name)"
    body=$(python3 -c '
import json, sys
key, lead = sys.argv[1], sys.argv[2]
print(json.dumps({
    "key": key,
    "name": key,
    "projectTypeKey": "software",
    "projectTemplateKey": "com.pyxis.greenhopper.jira:gh-simplified-agility-kanban",
    "lead": lead,
}))
' "${JIRA_PROJECT}" "${lead}")
    jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
        "${JIRA_BASE_URL}/rest/api/2/project" >/dev/null \
        || die "could not create Jira project ${JIRA_PROJECT} — see README.md's least-certain list for this call's shape"
fi

# --- 4. PROJ-1: the root issue, description links the page as a NAMED HYPERLINK ----------------

echo "=== ${JIRA_PROJECT}-1 (root issue) ==="
# Wiki markup, not HTML: Jira's v2 create API takes the description field as wiki markup, which
# Jira's own renderer turns into HTML for renderedFields.description — the field JiraClient
# actually reads (sdd-plan/src/main/java/sdd/plan/jira/JiraClient.java). "[text|url]" renders to
# <a href="url">text</a>, a NAMED hyperlink — this is deliberately not a bare URL anywhere in the
# description, so the only way LinkHarvester can find this page via the description is through
# the a[href] harvest Task 3's fix added, not the older bare-URL text scan.
description="order-service has no way for infrastructure to check whether it is ready for traffic. Full detail is in [the spec|${PAGE_URL}].

This is the seed issue for the sdd Atlassian rig's end-to-end verification run."
body=$(python3 -c '
import json, sys
project, summary, desc = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({"fields": {
    "project": {"key": project},
    "summary": summary,
    "description": desc,
    "issuetype": {"name": "Task"},
}}))
' "${JIRA_PROJECT}" "Add a health endpoint to ${REPO_NAME}" "${description}")
create_response="$(jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
    "${JIRA_BASE_URL}/rest/api/2/issue")" \
    || die "could not create ${JIRA_PROJECT}-1"
ROOT_KEY="$(printf '%s' "${create_response}" | json_get key)"
echo "  created ${ROOT_KEY}"

# Also a REMOTE LINK to the same page — the second discovery channel (JiraClient.fetchIssue's
# GET .../remotelink), so both channels the brief names are covered by one issue.
body=$(python3 -c '
import json, sys
url, title = sys.argv[1], sys.argv[2]
print(json.dumps({"object": {"url": url, "title": title}}))
' "${PAGE_URL}" "${PAGE_TITLE}")
jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
    "${JIRA_BASE_URL}/rest/api/2/issue/${ROOT_KEY}/remotelink" >/dev/null \
    || die "could not add a remote link on ${ROOT_KEY}"
echo "  added remote link to the same page"

# --- 5. PROJ-2: subtask -------------------------------------------------------------------------

echo "=== ${JIRA_PROJECT}-2 (subtask of ${ROOT_KEY}) ==="
body=$(python3 -c '
import json, sys
project, parent, summary = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({"fields": {
    "project": {"key": project},
    "parent": {"key": parent},
    "summary": summary,
    "issuetype": {"name": "Sub-task"},
}}))
' "${JIRA_PROJECT}" "${ROOT_KEY}" "Write /healthz integration test")
create_response="$(jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
    "${JIRA_BASE_URL}/rest/api/2/issue")" \
    || die "could not create the ${ROOT_KEY} subtask"
SUBTASK_KEY="$(printf '%s' "${create_response}" | json_get key)"
echo "  created ${SUBTASK_KEY}"

# --- 6. PROJ-3: "is blocked by" linked issue --------------------------------------------------

echo "=== ${JIRA_PROJECT}-3 (blocks ${ROOT_KEY}) ==="
body=$(python3 -c '
import json, sys
project, summary = sys.argv[1], sys.argv[2]
print(json.dumps({"fields": {
    "project": {"key": project},
    "summary": summary,
    "issuetype": {"name": "Task"},
}}))
' "${JIRA_PROJECT}" "Provision the health-check load balancer target group")
create_response="$(jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
    "${JIRA_BASE_URL}/rest/api/2/issue")" \
    || die "could not create the blocking issue"
BLOCKER_KEY="$(printf '%s' "${create_response}" | json_get key)"
echo "  created ${BLOCKER_KEY}"

body=$(python3 -c '
import json, sys
inward, outward = sys.argv[1], sys.argv[2]
print(json.dumps({
    "type": {"name": "Blocks"},
    "inwardIssue": {"key": inward},
    "outwardIssue": {"key": outward},
}))
' "${ROOT_KEY}" "${BLOCKER_KEY}")
jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
    "${JIRA_BASE_URL}/rest/api/2/issueLink" >/dev/null \
    || die "could not link ${BLOCKER_KEY} as blocking ${ROOT_KEY}"
echo "  linked: ${ROOT_KEY} is blocked by ${BLOCKER_KEY}"

# --- 7. a clarifying comment on the root issue --------------------------------------------------

echo "=== comment on ${ROOT_KEY} ==="
body=$(python3 -c '
import json, sys
print(json.dumps({"body": sys.argv[1]}))
' "Clarification: /healthz must not depend on the order-events topic's connectivity — only the database. Kafka connectivity already has its own separate monitoring.")
jira_curl -f -H "Content-Type: application/json" -X POST -d "${body}" \
    "${JIRA_BASE_URL}/rest/api/2/issue/${ROOT_KEY}/comment" >/dev/null \
    || die "could not comment on ${ROOT_KEY}"
echo "  added"

# --- 8. dump raw fixtures, using the EXACT calls sdd's own clients make ------------------------

echo
echo "=== dumping fixtures ==="

ISSUE_FIELDS="summary,description,issuelinks,subtasks,comment,status,updated"

root_issue_json="$(jira_curl -f "${JIRA_BASE_URL}/rest/api/2/issue/${ROOT_KEY}?expand=renderedFields&fields=${ISSUE_FIELDS}")" \
    || die "could not re-fetch ${ROOT_KEY} for fixture dump"
write_fixture "sdd-plan/src/test/resources/jira/issue-with-named-hyperlink.json" "${root_issue_json}"

remotelink_json="$(jira_curl -f "${JIRA_BASE_URL}/rest/api/2/issue/${ROOT_KEY}/remotelink")" \
    || die "could not re-fetch ${ROOT_KEY}'s remote links for fixture dump"
write_fixture "sdd-plan/src/test/resources/jira/remotelink.json" "${remotelink_json}"

linked_issue_json="$(jira_curl -f "${JIRA_BASE_URL}/rest/api/2/issue/${BLOCKER_KEY}?expand=renderedFields&fields=${ISSUE_FIELDS}")" \
    || die "could not re-fetch ${BLOCKER_KEY} for fixture dump"
write_fixture "sdd-plan/src/test/resources/jira/linked-issue.json" "${linked_issue_json}"

subtask_issue_json="$(jira_curl -f "${JIRA_BASE_URL}/rest/api/2/issue/${SUBTASK_KEY}?expand=renderedFields&fields=${ISSUE_FIELDS}")" \
    || die "could not re-fetch ${SUBTASK_KEY} for fixture dump"
write_fixture "sdd-plan/src/test/resources/jira/subtask-issue.json" "${subtask_issue_json}"

page_json="$(confluence_curl -f "${CONFLUENCE_BASE_URL}/rest/api/content/${PAGE_ID}?expand=body.storage,version,space")" \
    || die "could not re-fetch the Confluence page for fixture dump"
write_fixture "sdd-plan/src/test/resources/confluence/page.json" "${page_json}"

bb_repo_slug="$(printf '%s' "${REPO_NAME}" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]/-/g')"
bb_project_json="$(bb_curl -f "${BITBUCKET_BASE_URL}/rest/api/1.0/projects/${BITBUCKET_PROJECT}")" \
    || die "could not fetch Bitbucket project ${BITBUCKET_PROJECT} for fixture dump — did mirror.sh run?"
write_fixture "sdd-cli/src/test/resources/bitbucket/project.json" "${bb_project_json}"

bb_branch_json="$(bb_curl -f "${BITBUCKET_BASE_URL}/rest/api/1.0/projects/${BITBUCKET_PROJECT}/repos/${bb_repo_slug}/default-branch")" \
    || die "could not fetch ${REPO_NAME}'s default branch for fixture dump — did mirror.sh mirror it?"
write_fixture "sdd-cli/src/test/resources/bitbucket/default-branch.json" "${bb_branch_json}"

bb_prs_json="$(bb_curl -f "${BITBUCKET_BASE_URL}/rest/api/1.0/projects/${BITBUCKET_PROJECT}/repos/${bb_repo_slug}/pull-requests?state=OPEN")" \
    || die "could not list pull requests for ${REPO_NAME} for fixture dump"
write_fixture "sdd-cli/src/test/resources/bitbucket/pull-requests-empty.json" "${bb_prs_json}"

echo
echo "=== summary ==="
echo "Root issue:    ${ROOT_KEY}  (${JIRA_BASE_URL}/browse/${ROOT_KEY})"
echo "Subtask:       ${SUBTASK_KEY}"
echo "Blocking issue: ${BLOCKER_KEY}"
echo "Confluence page: ${PAGE_URL}"
echo
echo "Fixtures written (${#FIXTURES_WRITTEN[@]}):"
for f in "${FIXTURES_WRITTEN[@]}"; do
    echo "  ${f}"
done
echo
echo "Hand-written fixtures NOT regenerated (different scenarios this seed does not model —"
echo "diff them by hand if you extend this script):"
for f in "${FIXTURES_SKIPPED[@]}"; do
    echo "  ${f}"
done
echo
echo "sdd.yml's atlassian.jira/confluence/bitbucket base_url should be:"
echo "  ${JIRA_BASE_URL}"
echo "  ${CONFLUENCE_BASE_URL}"
echo "  ${BITBUCKET_BASE_URL}"
echo "Run: sdd plan ${ROOT_KEY}"
