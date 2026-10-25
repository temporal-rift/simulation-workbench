#!/usr/bin/env bash
# Measures what one light simulation lane can play when every case opens and closes its own execution context
# (README, "Measuring capacity"). Provisions exactly one lane with the baseline bundle through the infrastructure
# repository's workbench E2E harness, caps the lane's CPU and memory to a reference spec, freezes the
# `baseline-policy-comparison` template with its defaults, runs it on that one lane, repeats the run, samples the
# lane's containers while it runs, and writes the report.
#
# Usage: bash scripts/measure-capacity.sh <infrastructure-checkout>
#
# The infrastructure checkout must have the game-service, timeline-service, read-service and simulation-workbench
# checkouts as siblings (the layout its harness builds from); the workbench built is the sibling one.
#
# Environment:
#   CAPACITY_REFERENCE_THREADS    Logical processors the lane may use (default 8: 4 cores / 8 threads).
#   CAPACITY_REFERENCE_MEMORY_MB  Memory the lane may use (default 16384: 16 GB).
#   CAPACITY_REPETITIONS          How many times the run is repeated on the lane (default 2, at least 2).
#   CAPACITY_REPORT               Where the report is written (default docs/capacity/per-game-context.md).
#   CAPACITY_OUT                  Where the raw samples are kept (default target/capacity).
#   CAPACITY_TIMEOUT_MINUTES      Longest a run may take (default 360).
#   CAPACITY_HOST                 Description of the host machine; derived when unset.
#   CAPACITY_KEEP                 Set to 1 to leave the provisioned deployment running afterwards.
#   MAVEN_SETTINGS                Maven settings the image builds use (default: the contents of ~/.m2/settings.xml).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
infra="${1:?Usage: measure-capacity.sh <infrastructure-checkout>}"
infra="$(cd "$infra" && pwd)"

threads="${CAPACITY_REFERENCE_THREADS:-8}"
memory_mb="${CAPACITY_REFERENCE_MEMORY_MB:-16384}"
repetitions="${CAPACITY_REPETITIONS:-2}"
report="${CAPACITY_REPORT:-$repo_root/docs/capacity/per-game-context.md}"
out="${CAPACITY_OUT:-$repo_root/target/capacity}"
timeout_minutes="${CAPACITY_TIMEOUT_MINUTES:-360}"

lane_project="temporal-rift-sim-wbe2e-1"
workbench_project="temporal-rift-workbench-e2e"
issuer_url="http://localhost:19000/default"
workbench_url="http://localhost:18200"

fail() { echo "Capacity measurement failed: $1" >&2; exit 1; }

for tool in docker curl jq node; do
  command -v "$tool" > /dev/null || fail "$tool is required."
done
[[ "$threads" =~ ^[0-9]+$ && "$threads" -ge 1 ]] || fail "CAPACITY_REFERENCE_THREADS must be a number, got '$threads'."
[[ "$memory_mb" =~ ^[0-9]+$ && "$memory_mb" -ge 1 ]] || fail "CAPACITY_REFERENCE_MEMORY_MB must be a number, got '$memory_mb'."
[[ "$repetitions" =~ ^[0-9]+$ && "$repetitions" -ge 2 ]] || fail "CAPACITY_REPETITIONS must be at least 2, got '$repetitions'."
for dir in game-service timeline-service read-service simulation-workbench; do
  [[ -d "$infra/../$dir" ]] || fail "sibling checkout not found: $infra/../$dir."
done
[[ -f "$infra/scripts/provision-workbench-e2e.sh" ]] || fail "$infra is not an infrastructure checkout with the workbench harness."

docker_cpus="$(docker info --format '{{.NCPU}}')"
docker_memory_mb="$(( $(docker info --format '{{.MemTotal}}') / 1048576 ))"
[[ "$docker_cpus" -ge "$threads" ]] || fail "Docker has $docker_cpus logical processors, fewer than the reference spec's $threads."

existing="$(docker compose ls --all --format json | jq -r '.[].Name | select(startswith("temporal-rift-sim-") or startswith("temporal-rift-workbench-e2e") or startswith("temporal-rift-wbe2e-"))')"
[[ -z "$existing" ]] || fail "these projects exist already and belong to someone else: $(echo "$existing" | tr '\n' ' ')."

mkdir -p "$out" "$(dirname "$report")"
rm -f "$out"/*.tsv "$out"/*.json "$out"/*.txt

if [[ -z "${MAVEN_SETTINGS:-}" ]]; then
  [[ -f "$HOME/.m2/settings.xml" ]] || fail "MAVEN_SETTINGS is unset and ~/.m2/settings.xml does not exist."
  MAVEN_SETTINGS="$(cat "$HOME/.m2/settings.xml")"
fi
export MAVEN_SETTINGS

new_uuid() { node -e 'console.log(crypto.randomUUID())'; }

sampler_pid=""
cleanup() {
  [[ -z "$sampler_pid" ]] || kill "$sampler_pid" 2> /dev/null || true
  [[ "${CAPACITY_KEEP:-}" == 1 ]] || (cd "$infra" && bash scripts/cleanup-workbench-e2e.sh > "$out/cleanup.txt" 2>&1) || true
}
trap cleanup EXIT

echo "== Provisioning one light lane with the baseline bundle"
(cd "$infra" && WORKBENCH_E2E_LANES=1 WORKBENCH_E2E_RUN_ID=capacity bash scripts/provision-workbench-e2e.sh)

lane_projects="$(docker compose ls --format json | jq -r '.[].Name | select(startswith("temporal-rift-sim-"))')"
[[ "$lane_projects" == "$lane_project" ]] || fail "exactly one lane project must exist, found: $lane_projects."

lane_containers="$(docker ps --filter "label=com.docker.compose.project=$lane_project" --format '{{.Names}}')"
[[ -n "$lane_containers" ]] || fail "the lane has no running container."

echo "== Capping the lane to $threads logical processors and $memory_mb MiB"
cpuset="0-$((threads - 1))"
limit_sum=0
for container in $lane_containers; do
  docker update --cpuset-cpus "$cpuset" "$container" > /dev/null
  limit="$(docker inspect -f '{{.HostConfig.Memory}}' "$container")"
  [[ "$limit" -gt 0 ]] || fail "$container has no memory limit."
  limit_sum=$((limit_sum + limit / 1048576))
done
[[ "$limit_sum" -le "$memory_mb" ]] || fail "the lane's container limits add up to $limit_sum MiB, over the cap of $memory_mb MiB."

lane_footprint() {
  for container in $lane_containers; do
    local service
    service="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$container")"
    local heap
    heap="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container" \
      | sed -n 's/^\(JAVA_TOOL_OPTIONS\|KAFKA_HEAP_OPTS\)=\(.*\)$/\2/p' | tr '\n' ' ' | sed 's/ *$//')"
    local effective
    effective="$(docker exec "$container" sh -c 'java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E " MaxHeapSize "' 2> /dev/null \
      | awk '{printf "%d", $4 / 1048576}' || true)"
    printf '%s\t%s\t%s\t%s\n' "$service" "$(( $(docker inspect -f '{{.HostConfig.Memory}}' "$container") / 1048576 ))" "${heap:-n/a}" "${effective:-n/a}"
  done | sort
}
lane_footprint > "$out/limits.tsv"

host="${CAPACITY_HOST:-}"
if [[ -z "$host" ]]; then
  if command -v powershell.exe > /dev/null; then
    host="$(powershell.exe -NoProfile -Command "\$c = Get-CimInstance Win32_Processor | Select-Object -First 1; \$m = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB); \"\$(\$c.Name.Trim()) (\$(\$c.NumberOfCores) cores, \$(\$c.NumberOfLogicalProcessors) threads), \$m GB\"" | tr -d '\r')"
  elif [[ -r /proc/cpuinfo ]]; then
    host="$(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo), $(nproc) threads, $(awk '/MemTotal/ {printf "%d GB", $2 / 1048576}' /proc/meminfo)"
  else
    host="$(uname -srm)"
  fi
fi

echo "== Reading the lane's provenance"
lane_manifest="$infra/simulation/lanes/wbe2e-1/manifest.json"
[[ -f "$lane_manifest" ]] || fail "the lane manifest $lane_manifest is missing."
jq '{effectiveRulesDigest, effectiveContentDigest, rulesArtifactDigest, contentArtifactDigest}' "$lane_manifest" > "$out/bundle.json" 2> /dev/null || cp "$lane_manifest" "$out/bundle.json"
for service in game-service timeline-service read-service; do
  printf '%s\t%s\n' "$service" "$(docker image inspect "temporal-rift/sim-$service:wbe2e" --format '{{.Id}}')"
done > "$out/images.tsv"

token() {
  curl --silent --show-error --fail --max-time 20 -X POST \
    --data-urlencode "grant_type=client_credentials" --data-urlencode "client_id=designer" \
    --data-urlencode "client_secret=not-checked" \
    --data-urlencode "scope=simulation:read simulation:write simulation:observe" \
    "$issuer_url/token" | jq -r .access_token
}
api() { # api METHOD PATH [BODY] -> prints the response, fails on an error status
  local method="$1" path="$2" body="${3:-}"
  local args=(--silent --show-error --fail-with-body --max-time 60 -X "$method" -H "Authorization: Bearer $(token)"
    -H "Content-Type: application/json" -H "Idempotency-Key: $(new_uuid)")
  [[ -z "$body" ]] || args+=(--data "$body")
  curl "${args[@]}" "$workbench_url$path"
}
psql_workbench() {
  local container
  container="$(docker ps --filter "label=com.docker.compose.project=$workbench_project" \
    --filter "label=com.docker.compose.service=workbench-postgres" --format '{{.Names}}')"
  docker exec "$container" psql -U workbench -d workbench -At -F $'\t' -c "$1"
}
metric_sums() { # prints "<metric> <sum>" for every capacity timer, summed over its tags
  curl --silent --show-error --fail --max-time 20 "$workbench_url/actuator/prometheus" \
    | awk '/^workbench_(case_attempt|policy_decision|lane_wait|lane_context_close|lane_reset)_seconds_(sum|count)/ {
        name = $1; sub(/\{.*/, "", name); total[name] += $NF
      } END { for (name in total) printf "%s %.6f\n", name, total[name] }' | sort
}

echo "== Freezing the baseline-policy-comparison template with its defaults"
bundle="$(api GET '/api/v1/lane-bundles' | jq '.items[0]')"
[[ "$(api GET '/api/v1/lane-bundles' | jq '.total')" == "1" ]] || fail "the workbench lists more than one bundle."
template="$(api GET '/api/v1/experiment-templates' | jq '.items[] | select(.templateId == "baseline-policy-comparison")')"
[[ -n "$template" ]] || fail "the workbench offers no baseline-policy-comparison template."
manifest="$(jq -n --argjson bundle "$bundle" --argjson template "$template" '
  $template.defaults + {
    schemaVersion: 1,
    name: "capacity of per-game contexts",
    variants: [{
      label: "baseline",
      rulesArtifact: $bundle.rulesArtifact,
      contentArtifact: $bundle.contentArtifact,
      effectiveRulesDigest: $bundle.effectiveRulesDigest,
      effectiveContentDigest: $bundle.effectiveContentDigest
    }],
    services: $bundle.services,
    contracts: $bundle.contracts
  }')"
echo "$manifest" > "$out/manifest.json"
experiment_id="$(api POST '/api/v1/experiments' "$manifest" | jq -r .experimentId)"
[[ -n "$experiment_id" && "$experiment_id" != "null" ]] || fail "the experiment was not created."

echo "== Sampling the lane's containers"
samples="$out/resource-samples.txt"
: > "$samples"
(
  while true; do
    printf '#%s\n' "$(date +%s)" >> "$samples"
    # shellcheck disable=SC2086
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' $lane_containers >> "$samples" 2> /dev/null || true
    sleep 2
  done
) &
sampler_pid=$!

: > "$out/runs.tsv"
for repetition in $(seq 1 "$repetitions"); do
  echo "== Repetition $repetition of $repetitions"
  metric_sums > "$out/metrics-before-$repetition.txt"
  run_id="$(api POST "/api/v1/experiments/$experiment_id/runs" '{}' | jq -r .runId)"
  [[ -n "$run_id" && "$run_id" != "null" ]] || fail "the run did not start."
  deadline=$(( $(date +%s) + timeout_minutes * 60 ))
  while true; do
    state="$(api GET "/api/v1/runs/$run_id" | jq -r .state)"
    [[ "$state" == "COMPLETED" || "$state" == "FAILED" || "$state" == "CANCELLED" ]] && break
    [[ "$(date +%s)" -lt "$deadline" ]] || fail "run $run_id did not finish within $timeout_minutes minutes."
    sleep 20
  done
  [[ "$state" == "COMPLETED" ]] || fail "run $run_id ended $state."
  metric_sums > "$out/metrics-after-$repetition.txt"
  printf '%s\t%s\n' "$repetition" "$run_id" >> "$out/runs.tsv"
done

kill "$sampler_pid" 2> /dev/null || true
sampler_pid=""

echo "== Computing the figures"
mib() { # converts a docker size such as 512MiB or 1.2GiB to MiB
  awk -v s="$1" 'BEGIN { v = s; u = s; gsub(/[0-9.]/, "", u); gsub(/[^0-9.]/, "", v);
    if (u == "GiB") v *= 1024; else if (u == "KiB") v /= 1024; else if (u == "B") v /= 1048576; printf "%.0f", v }'
}
peaks="$(awk -F, '
  function mib(text,   parts, value, unit) {
    split(text, parts, " "); value = parts[1]; unit = value; gsub(/[0-9.]/, "", unit); gsub(/[^0-9.]/, "", value)
    if (unit == "GiB") return value * 1024
    if (unit == "KiB") return value / 1024
    if (unit == "B") return value / 1048576
    return value
  }
  /^#/ { next }
  {
    name = $1; sub(/^temporal-rift-sim-wbe2e-1-/, "", name); sub(/-[0-9]+$/, "", name)
    cpu = $2; gsub(/%/, "", cpu); used = mib($3)
    if (used > memory[name]) memory[name] = used
    if (cpu + 0 > cpus[name]) cpus[name] = cpu + 0
  }
  END { for (name in memory) printf "%s\t%.0f\t%.0f\n", name, memory[name], cpus[name] }' "$samples" | sort)"
echo "$peaks" > "$out/peaks.tsv"

metric_delta() { # metric_delta <repetition> <metric>
  local before after
  before="$(awk -v m="$2" '$1 == m {print $2}' "$out/metrics-before-$1.txt")"
  after="$(awk -v m="$2" '$1 == m {print $2}' "$out/metrics-after-$1.txt")"
  awk -v a="${after:-0}" -v b="${before:-0}" 'BEGIN { printf "%.3f", a - b }'
}

{
  echo "# Capacity of per-game execution contexts"
  echo
  echo "Games per hour and footprint of one light simulation lane that plays every case in its own execution context,"
  echo "measured by \`scripts/measure-capacity.sh\` (README, \"Measuring capacity\")."
  echo
  echo "## Method"
  echo
  echo "- **Lane.** Exactly one light lane (\`$lane_project\`) serving the baseline bundle; no second lane project was"
  echo "  provisioned at any point. It was provisioned by the infrastructure repository's workbench E2E harness with"
  echo "  \`WORKBENCH_E2E_LANES=1\`, which also starts the issuer, the lane recycler, an ordinary deployment and the workbench."
  echo "- **Workload.** The \`baseline-policy-comparison\` template with its defaults: seed \`42\`, every faction set of 3, 4 and"
  echo "  5 players under all cyclic rotations, policies \`random-v1\` and \`faction-greedy-v1\` on every seat, one variant,"
  echo "  one concurrent case (the lane count). The run was repeated $repetitions times on the same lane."
  echo "- **Reference spec.** $((threads / 2)) cores / $threads threads and $((memory_mb / 1024)) GB. The lane's containers were capped to"
  echo "  the logical processors \`$cpuset\` (\`docker update --cpuset-cpus\`); their memory limits come from the lane overlay and"
  echo "  add up to $limit_sum MiB, within the cap of $memory_mb MiB."
  echo "- **Host.** $host. Docker had $docker_cpus logical processors and $docker_memory_mb MiB of memory."
  echo "- **Samples.** \`docker stats\` for every container of the lane every two seconds during the runs; the heap of a"
  echo "  JVM is its declared options and the maximum heap the JVM reports."
  echo "- **Wall time.** A game's wall time is its attempt's, from its start to its final state; closing its context follows it."
  echo
  echo "## Build and bundle"
  echo
  echo "| Service | Image |"
  echo "|---|---|"
  while IFS=$'\t' read -r service image; do echo "| \`$service\` | \`$image\` |"; done < "$out/images.tsv"
  echo
  echo "| | Digest |"
  echo "|---|---|"
  echo "| Effective rules | \`$(jq -r .effectiveRulesDigest "$out/bundle.json")\` |"
  echo "| Effective content | \`$(jq -r .effectiveContentDigest "$out/bundle.json")\` |"
  echo
  echo "## Result"
  echo
  echo "| Repetition | Games | Wall time (s) | Games per hour |"
  echo "|---:|---:|---:|---:|"
  rates=()
  while IFS=$'\t' read -r repetition run_id; do
    row="$(psql_workbench "SELECT count(*) FILTER (WHERE c.state = 'SUCCEEDED'),
        round(extract(epoch FROM (r.finished_at - r.started_at))::numeric, 0),
        round((count(*) FILTER (WHERE c.state = 'SUCCEEDED') * 3600 / extract(epoch FROM (r.finished_at - r.started_at)))::numeric, 0)
      FROM run r JOIN run_case c ON c.run_id = r.run_id WHERE r.run_id = '$run_id' GROUP BY r.run_id")"
    IFS=$'\t' read -r games seconds rate <<< "$row"
    echo "| $repetition | $games | $seconds | $rate |"
    rates+=("$rate")
  done < "$out/runs.tsv"
  echo
  first="${rates[0]}"; second="${rates[1]}"
  echo "The run-to-run variation between repetitions 1 and 2 is $(awk -v a="$first" -v b="$second" 'BEGIN { d = a - b; if (d < 0) d = -d; printf "%d games per hour (%.1f%% of repetition 1)", d, a > 0 ? d * 100 / a : 0 }')."
  echo
  run_ids="$(awk -F'\t' '{printf "%s'\''%s'\''", (NR > 1 ? "," : ""), $2}' "$out/runs.tsv")"
  echo "### Games measured per player count and policy"
  echo
  echo "| Players | Policy | Games |"
  echo "|---:|---|---:|"
  psql_workbench "SELECT c.player_count, (c.seats_json::jsonb)->0->>'policyId', count(*) FROM run_case c
      WHERE c.run_id IN ($run_ids) AND c.state = 'SUCCEEDED' GROUP BY 1, 2 ORDER BY 1, 2" \
    | awk -F'\t' '{printf "| %s | `%s` | %s |\n", $1, $2, $3}'
  echo
  echo "### Wall time per game, in seconds"
  echo
  echo "| Players | Policy | q0.1 | q0.5 | q0.9 |"
  echo "|---:|---|---:|---:|---:|"
  psql_workbench "SELECT c.player_count, (c.seats_json::jsonb)->0->>'policyId',
        round((percentile_cont(0.1) WITHIN GROUP (ORDER BY extract(epoch FROM (a.finished_at - a.started_at))))::numeric, 2),
        round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (a.finished_at - a.started_at))))::numeric, 2),
        round((percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM (a.finished_at - a.started_at))))::numeric, 2)
      FROM case_attempt a JOIN run_case c ON c.case_id = a.case_id
      WHERE c.run_id IN ($run_ids) AND a.state = 'SUCCEEDED' GROUP BY 1, 2 ORDER BY 1, 2" \
    | awk -F'\t' '{printf "| %s | `%s` | %s | %s | %s |\n", $1, $2, $3, $4, $5}'
  echo
  echo "### Where the wall time goes"
  echo
  echo "Per repetition, the share of the attempts' wall time and the context closes that went to play (the attempt less"
  echo "decisions and waits), policy decisions, waits for the services, and closing the contexts."
  echo
  echo "| Repetition | Play | Policy decisions | Service waits | Context closes | Lane resets |"
  echo "|---:|---:|---:|---:|---:|---:|"
  while IFS=$'\t' read -r repetition _; do
    attempt="$(metric_delta "$repetition" workbench_case_attempt_seconds_sum)"
    decisions="$(metric_delta "$repetition" workbench_policy_decision_seconds_sum)"
    waits="$(metric_delta "$repetition" workbench_lane_wait_seconds_sum)"
    closes="$(metric_delta "$repetition" workbench_lane_context_close_seconds_sum)"
    resets="$(metric_delta "$repetition" workbench_lane_reset_seconds_count)"
    awk -v r="$repetition" -v a="$attempt" -v d="$decisions" -v w="$waits" -v c="$closes" -v x="$resets" 'BEGIN {
      total = a + c; play = a - d - w
      printf "| %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %d |\n", r, play * 100 / total, d * 100 / total, w * 100 / total, c * 100 / total, x }'
  done < "$out/runs.tsv"
  echo
  echo "### Failed attempts by failure code"
  echo
  failures="$(psql_workbench "SELECT a.failure_code, count(*) FROM case_attempt a JOIN run_case c ON c.case_id = a.case_id
      WHERE c.run_id IN ($run_ids) AND a.state = 'FAILED' GROUP BY 1 ORDER BY 1")"
  if [[ -z "$failures" ]]; then
    echo "None."
  else
    echo "| Failure code | Attempts |"
    echo "|---|---:|"
    echo "$failures" | awk -F'\t' '{printf "| `%s` | %s |\n", $1, $2}'
  fi
  echo
  echo "## Footprint of the lane"
  echo
  echo "| Container | Limit (MiB) | Peak memory (MiB) | Peak CPU (% of one processor) | Declared heap | Maximum heap (MiB) |"
  echo "|---|---:|---:|---:|---|---:|"
  while IFS=$'\t' read -r service limit heap effective; do
    peak="$(awk -F'\t' -v s="$service" '$1 == s {printf "%s\t%s", $2, $3}' "$out/peaks.tsv")"
    echo "| \`$service\` | $limit | ${peak%%$'\t'*} | ${peak##*$'\t'} | \`$heap\` | $effective |"
  done < "$out/limits.tsv"
} > "$report"

echo "Report written: $report"
