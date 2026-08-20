#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<EOF
Usage: $0 --token <api_token> [--root-token <root_token>] [--base-url <url>] [-- <maven args>]

Run the Endee Java functional test suite (JUnit 5, src/test/java) against a live
server. Sets environment variables and invokes 'mvn test' from the project root
(two levels up from this script, where pom.xml lives).

Options:
  --token <api_token>          Database-level API token (required).
                               Sets ENDEE_TOKEN for the test run.
  --root-token <root_token>    Root/admin token (optional).
                               Sets NDD_ROOT_TOKEN; enables AdminTest.
                               AdminTest is automatically skipped if omitted.
  --base-url <url>             Override the server base URL (default: the
                               client's own default - derived from the
                               token's region, or the local server).
                               Sets ENDEE_BASE_URL.
  --                           Pass all remaining arguments directly to Maven.
                               Use "-Dtest=ClassName#method" to run a single test.
  -h, --help                   Show this help message and exit.

Examples:
  # Run the full test suite (AdminTest skipped)
  $0 --token <api_token>

  # Run against a local dev server
  $0 --token <api_token> --base-url http://localhost:8080/api/v2

  # Enable AdminTest by supplying a root token
  $0 --token <api_token> --root-token <root_token>

  # Run a single test class
  $0 --token <api_token> -- -Dtest=FilteringTest

  # Run a single test method
  $0 --token <api_token> -- -Dtest=FilteringTest#filterByEqOperator
EOF
  exit "${1:-1}"
}

TOKEN=""
ROOT_TOKEN=""
BASE_URL=""
MVN_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --token)       TOKEN="$2";      shift 2 ;;
    --root-token)  ROOT_TOKEN="$2"; shift 2 ;;
    --base-url)    BASE_URL="$2";   shift 2 ;;
    --)            shift; MVN_ARGS+=("$@"); break ;;
    -h|--help)     usage 0 ;;
    *)             echo "Unknown argument: $1"; usage ;;
  esac
done

if [[ -z "$TOKEN" ]]; then
  echo "Error: --token is required"
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

export ENDEE_TOKEN="$TOKEN"
if [[ -n "$ROOT_TOKEN" ]]; then
  export NDD_ROOT_TOKEN="$ROOT_TOKEN"
fi
if [[ -n "$BASE_URL" ]]; then
  export ENDEE_BASE_URL="$BASE_URL"
fi

echo "Running tests ..."
mvn test "${MVN_ARGS[@]+"${MVN_ARGS[@]}"}"
