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
#   MAVEN_SETTINGS                Maven settings the image builds use; by default a settings file with a `github` server
#                                 entry made of GITHUB_ACTOR (default: the `gh` user) and GITHUB_TOKEN.
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

case "$(uname -s)" in
  MINGW*|MSYS*)
    # Git Bash would rewrite the POSIX-style workspace path the harness hands to Compose in its environment.
    export MSYS2_ENV_CONV_EXCL="WORKBENCH_E2E_"
    ;;
esac

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
  [[ -n "${GITHUB_TOKEN:-}" ]] || fail "MAVEN_SETTINGS and GITHUB_TOKEN are both unset; the image builds cannot read GitHub Packages."
  actor="${GITHUB_ACTOR:-$(gh api user --jq .login)}"
  MAVEN_SETTINGS="<settings><servers><server><id>github</id><username>$actor</username><password>$GITHUB_TOKEN</password></server></servers></settings>"
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
    local effective=""
    # Only JAVA_TOOL_OPTIONS reaches a JVM started afresh in the container; Kafka reads KAFKA_HEAP_OPTS in its own script.
    if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container" | grep -q '^JAVA_TOOL_OPTIONS='; then
      effective="$(docker exec "$container" sh -c 'java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E " MaxHeapSize "' 2> /dev/null \
        | awk '{printf "%d", $4 / 1048576}' || true)"
    fi
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
# A lane keeps the game a case played, and a game id follows from the case, so replaying a case on the same lane
# stalls: repetition k plays the template's first seed plus k - 1 and has an experiment of its own.
freeze_experiment() {
  local manifest_k
  manifest_k="$(jq --argjson shift "$(($1 - 1))" '.seeds = [(((.seeds[0] | tonumber) + $shift) | tostring)]' <<< "$manifest")"
  api POST '/api/v1/experiments' "$manifest_k" | jq -r .experimentId
}

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
  experiment_id="$(freeze_experiment "$repetition")"
  [[ -n "$experiment_id" && "$experiment_id" != "null" ]] || fail "the experiment was not created."
  run_id="$(api POST "/api/v1/experiments/$experiment_id/runs" '{}' | jq -r .runId)"
  [[ -n "$run_id" && "$run_id" != "null" ]] || fail "the run did not start."
  deadline=$(( $(date +%s) + timeout_minutes * 60 ))
  while true; do
    state="$(api GET "/api/v1/runs/$run_id" | jq -r .state)" || state="UNKNOWN"
    [[ "$state" == "COMPLETED" || "$state" == "FAILED" || "$state" == "CANCELLED" ]] && break
    [[ "$(date +%s)" -lt "$deadline" ]] || fail "run $run_id did not finish within $timeout_minutes minutes."
    sleep 20
  done
  [[ "$state" == "COMPLETED" ]] || fail "run $run_id ended $state."
  metric_sums > "$out/metrics-after-$repetition.txt"
  succeeded="$(psql_workbench "SELECT count(*) FROM run_case WHERE run_id = '$run_id' AND state = 'SUCCEEDED'")"
  [[ "$succeeded" -gt 0 ]] || fail "run $run_id played no game; see its failed attempts in the workbench database."
  printf '%s\t%s\n' "$repetition" "$run_id" >> "$out/runs.tsv"
done

kill "$sampler_pid" 2> /dev/null || true
sampler_pid=""

cat > "$out/setup.env" <<ENV
threads=$threads
memory_mb=$memory_mb
repetitions=$repetitions
lane_project=$lane_project
cpuset=$cpuset
limit_sum=$limit_sum
docker_cpus=$docker_cpus
docker_memory_mb=$docker_memory_mb
host="$host"
ENV
CAPACITY_OUT="$out" CAPACITY_REPORT="$report" bash "$script_dir/capacity-report.sh"
