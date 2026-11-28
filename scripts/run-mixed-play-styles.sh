#!/usr/bin/env bash
# On the simulation host: run-mixed-play-styles.sh INITIAL_DIR REVISION_DIR OUTPUT_DIR POSTGRES_CONTAINER
set -euo pipefail
initial="$1"
revision="$2"
out="$3"
postgres="$4"
root="$(cd "$(dirname "$0")" && pwd)"
base="${WORKBENCH_URL:-http://localhost:8080}"
issuer="${ISSUER_URL:-http://localhost:9000/default}"
mkdir -p "$out"
rm -f -- "$out/completed"
python3 "$root/merge-play-style-studies.py" "$initial" "$revision" "$out"
passes="$(jq -er '.styles["special-heavy-revision"].cells | any(.playerCount == null and .passes == true) | tostring' \
  "$out/combined-results.json")"
if [[ "$passes" == false ]]; then
  echo 'The revised style did not pass; initial mixed tables remain the measured population.'
  touch "$out/completed"
  exit 0
fi
python3 "$root/mixed-play-style-tables.py" "$out/combined-manifest.json" "$out/combined-results.json" "$out/mixed"
token() {
  curl -fsS -X POST --data-urlencode grant_type=client_credentials --data-urlencode client_id=campaign \
    --data-urlencode client_secret=x --data-urlencode 'scope=simulation:read simulation:write' "$issuer/token" | jq -er .access_token
}
api() { curl -fsS --max-time 1200 -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' "$@"; }
post() { api -X POST -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" "$@"; }
for players in 3 4 5; do
  name="mixed-$players"
  manifest="$out/mixed/$name.json"
  api -X POST -d @"$manifest" "$base/api/v1/experiment-previews?limit=1" > "$out/$name-preview.json"
  jq -e --slurpfile m "$manifest" '
    .caseCount == ((if $m[0].playerCounts[0] == 3 then 30 elif $m[0].playerCounts[0] == 4 then 20 else 5 end)
      * ($m[0].tables | length) * ($m[0].seeds | length))' "$out/$name-preview.json" >/dev/null
  post -d @"$manifest" "$base/api/v1/experiments" > "$out/$name-experiment.json"
  experiment="$(jq -er .experimentId "$out/$name-experiment.json")"
  post -d '{}' "$base/api/v1/experiments/$experiment/runs" > "$out/$name-start.json"
  run_id="$(jq -er .runId "$out/$name-start.json")"
  while :; do
    api "$base/api/v1/runs/$run_id" > "$out/$name-status.json"
    state="$(jq -er .state "$out/$name-status.json")"
    printf '%s %s %s\n' "$(date -u +%FT%TZ)" "$name" "$state"
    case "$state" in COMPLETED) break ;; FAILED|CANCELLED) exit 1 ;; esac
    sleep 30
  done
  jq -e '.counts.failed == 0 and .counts.cancelled == 0 and .counts.succeeded == .counts.requested' "$out/$name-status.json" >/dev/null
  api "$base/api/v1/runs/$run_id/report" > "$out/$name-report.json"
  docker exec -i "$postgres" psql -U temporal_rift -d temporal_rift -At -v ON_ERROR_STOP=1 -v "run_id=$run_id" \
    < "$root/play-style-cases.sql" > "$out/$name-cases.jsonl"
  python3 "$root/check-mixed-play-styles.py" "$manifest" "$out/$name-experiment.json" \
    "$out/$name-report.json" "$out/$name-cases.jsonl" \
    > "$out/$name-exposure.json"
done
sha256sum "$out"/*.json "$out"/*.jsonl "$out"/mixed/*.json > "$out/SHA256SUMS"
touch "$out/completed"
