#!/usr/bin/env bash
# Writes the capacity report from what `scripts/measure-capacity.sh` kept in its output directory and the workbench
# database it measured. Runs on its own when a measurement was kept with CAPACITY_KEEP=1.
#
# Usage: bash scripts/capacity-report.sh
#   CAPACITY_OUT     The directory the measurement wrote to (default target/capacity).
#   CAPACITY_REPORT  Where the report is written (default docs/capacity/per-game-context.md).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
out="${CAPACITY_OUT:-$repo_root/target/capacity}"
report="${CAPACITY_REPORT:-$repo_root/docs/capacity/per-game-context.md}"
[[ -f "$out/setup.env" ]] || { echo "No measurement in $out." >&2; exit 1; }
# shellcheck disable=SC1091
source "$out/setup.env"
workbench_project="temporal-rift-workbench-e2e"
samples="$out/resource-samples.txt"
mkdir -p "$(dirname "$report")"

psql_workbench() {
  local container
  container="$(docker ps --filter "label=com.docker.compose.project=$workbench_project" \
    --filter "label=com.docker.compose.service=workbench-postgres" --format '{{.Names}}')"
  docker exec "$container" psql -U workbench -d workbench -At -F $'\t' -c "$1"
}

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
  echo "  one concurrent case (the lane count). The run was repeated $repetitions times on the same lane; repetition k plays seed"
  echo "  42 + k - 1, because a lane keeps the game a case played and replaying the same case on it stalls."
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
  echo "The run-to-run variation between repetitions 1 and 2 is $(awk -v a="$first" -v b="$second" 'BEGIN { d = a - b; if (d < 0) d = -d; printf "%d games per hour (%.1f%% of repetition 1)", d, (a > 0 ? d * 100 / a : 0) }')."
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
  echo "Per repetition and kind of service wait: the seconds summed over the attempts, the samples, and the mean seconds per"
  echo "sample. A settle wait is a causal barrier sample, so the settle waits per game are those samples over the games played."
  echo
  echo "| Repetition | Kind | Seconds | Samples | Mean per sample (s) |"
  echo "|---:|---|---:|---:|---:|"
  settle_rows=()
  while IFS=$'\t' read -r repetition run_id; do
    games="$(psql_workbench "SELECT count(*) FROM run_case WHERE run_id = '$run_id' AND state = 'SUCCEEDED'")"
    kinds="$(awk '/^workbench_lane_wait_seconds_count\{kind="/ { k = $1; sub(/^[^"]*"/, "", k); sub(/".*/, "", k); print k }' \
      "$out/metrics-after-$repetition.txt" | sort)"
    if [[ -z "$kinds" ]]; then
      echo "| $repetition | not recorded | | | |"
      settle_rows+=("| $repetition | $games | not recorded |")
      continue
    fi
    causal_samples=0
    while IFS= read -r kind; do
      seconds="$(metric_delta "$repetition" "workbench_lane_wait_seconds_sum{kind=\"$kind\"}")"
      count="$(metric_delta "$repetition" "workbench_lane_wait_seconds_count{kind=\"$kind\"}")"
      [[ "$kind" != "CAUSAL_BARRIER" ]] || causal_samples="$count"
      awk -v r="$repetition" -v k="$kind" -v s="$seconds" -v n="$count" 'BEGIN {
        printf "| %s | `%s` | %.1f | %d | %.3f |\n", r, k, s, n, (n > 0 ? s / n : 0) }'
    done <<< "$kinds"
    settle_rows+=("$(awk -v r="$repetition" -v n="$causal_samples" -v g="$games" 'BEGIN {
      printf "| %s | %d | %s |", r, g, (g > 0 ? sprintf("%.0f", n / g) : "n/a") }')")
  done < "$out/runs.tsv"
  echo
  echo "| Repetition | Games | Settle waits per game |"
  echo "|---:|---:|---:|"
  printf '%s\n' "${settle_rows[@]}"
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
} > "$report.part"
mv "$report.part" "$report"

echo "Report written: $report"
