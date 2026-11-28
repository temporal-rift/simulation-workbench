#!/usr/bin/env bash
# Run on the simulation host. Usage: run-play-style-study.sh OUTPUT_DIR POSTGRES_CONTAINER
set -euo pipefail
out="$1"
postgres="$2"
root="$(cd "$(dirname "$0")" && pwd)"
base="${WORKBENCH_URL:-http://localhost:8080}"
issuer="${ISSUER_URL:-http://localhost:9000/default}"
mkdir -p "$out"
rm -f -- "$out/completed"
token() {
  curl -fsS -X POST --data-urlencode grant_type=client_credentials --data-urlencode client_id=campaign \
    --data-urlencode client_secret=x --data-urlencode 'scope=simulation:read simulation:write' "$issuer/token" | jq -er .access_token
}
api() { curl -fsS --max-time 1200 -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' "$@"; }
post() { api -X POST -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" "$@"; }
api "$base/api/v1/policies" > "$out/policies.json"
api "$base/api/v1/lane-bundles" | jq '{items: [.items[] | select(.laneCount > 1)]}' > "$out/bundles.json"
if [[ "${STYLE_STUDY_MODE:-initial}" == revision ]]; then
  python3 "$root/special_style_revision.py" "$out/policies.json" "$out/bundles.json" "$out/manifest.json"
else
  python3 "$root/play_style_study.py" "$out/policies.json" "$out/bundles.json" "$out/manifest.json"
fi
# A fresh seed tests the actual workbench, all player counts and all candidate digests before the study.
jq '.name = "Fixed style smoke" | .seeds = ["41999"]' "$out/manifest.json" > "$out/smoke-manifest.json"
run() {
  local name="$1" manifest="$2" experiment runid state
  api -X POST -d @"$manifest" "$base/api/v1/experiment-previews?limit=1" > "$out/$name-preview.json"
  jq -e --slurpfile m "$manifest" '
    .caseCount == (([$m[0].playerCounts[] | if . == 3 then 30 elif . == 4 then 20 else 5 end] | add)
      * ($m[0].tables | length) * ($m[0].seeds | length))' "$out/$name-preview.json" >/dev/null
  experiment="$(post -d @"$manifest" "$base/api/v1/experiments")"
  printf '%s\n' "$experiment" > "$out/$name-experiment.json"
  experiment="$(jq -er .experimentId <<< "$experiment")"
  post -d '{}' "$base/api/v1/experiments/$experiment/runs" > "$out/$name-start.json"
  runid="$(jq -er .runId "$out/$name-start.json")"
  while :; do
    api "$base/api/v1/runs/$runid" > "$out/$name-status.json"
    state="$(jq -er .state "$out/$name-status.json")"
    printf '%s %s %s\n' "$(date -u +%FT%TZ)" "$name" "$state"
    case "$state" in COMPLETED) break ;; FAILED|CANCELLED) return 1 ;; esac
    sleep 30
  done
  jq -e '.counts.failed == 0 and .counts.cancelled == 0 and .counts.succeeded == .counts.requested' "$out/$name-status.json" >/dev/null
  api "$base/api/v1/runs/$runid/report" > "$out/$name-report.json"
  jq -e '.complete == true and .analysisVersion == "6" and .analysisSeed == "1"' "$out/$name-report.json" >/dev/null
  jq -e --slurpfile m "$manifest" '
    [.cohorts[] | select(.seatIndex != null) | .policyDigest] | unique | sort ==
    ([$m[0].policies[].artifactDigest] | unique | sort)' "$out/$name-report.json" >/dev/null
  for kind in cases facts; do
    docker exec -i "$postgres" psql -U temporal_rift -d temporal_rift -At -v ON_ERROR_STOP=1 -v "run_id=$runid" \
      < "$root/play-style-$kind.sql" > "$out/$name-$kind.jsonl"
  done
}
run smoke "$out/smoke-manifest.json"
run validation "$out/manifest.json"
python3 "$root/analyze_play_styles.py" "$out/manifest.json" "$out/validation-experiment.json" \
  "$out/validation-report.json" "$out/validation-cases.jsonl" \
  "$out/validation-facts.jsonl" "$out/results.json"
if [[ "${STYLE_STUDY_MODE:-initial}" != revision ]]; then
  python3 "$root/mixed_play_style_tables.py" "$out/manifest.json" "$out/results.json" "$out/mixed"
  for players in 3 4 5; do
    name="mixed-$players"
    run "$name" "$out/mixed/$name.json"
    python3 "$root/check_mixed_play_styles.py" "$out/mixed/$name.json" "$out/$name-experiment.json" "$out/$name-report.json" \
      "$out/$name-cases.jsonl" > "$out/$name-exposure.json"
  done
fi
sha256sum "$out"/*.json "$out"/*.jsonl > "$out/SHA256SUMS"
touch "$out/completed"
