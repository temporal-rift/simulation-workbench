import type { FidelityCase, FidelityCheck, FidelityEnvironment } from '../api/generated'
import { FIDELITY_RESULT_LABELS, FIDELITY_STATE_LABELS } from './fidelityView'

/** The name a check's report is committed under: its seed and the game-service revision it ran on. */
export function reportFilename(check: FidelityCheck): string {
  return `report-${check.seed}-${check.simulator.services.gameService.sourceRevision}.md`
}

function environmentRows(label: string, environment: FidelityEnvironment | null): string[] {
  if (!environment) {
    return [`| ${label} | — | — | — |`]
  }
  return Object.entries(environment.services).map(
    ([service, build]) => `| ${label} | ${service} | \`${build.sourceRevision}\` | \`${build.imageDigest}\` |`,
  )
}

function detailOf(item: FidelityCase): string {
  if (item.firstDivergence) {
    return `differs at step ${item.firstDivergence.step}: ${item.firstDivergence.kind}`
  }
  return item.failure ? `${item.failure.message} (${item.failure.code})` : ''
}

/**
 * A served check as the report committed beside the design records: the fields of the record as the service returned
 * them, and the cases that did not match, so a reader sees what a failed check failed on.
 */
export function fidelityReport(check: FidelityCheck, unmatched: readonly FidelityCase[]): string {
  const { counts } = check
  const lines = [
    `# Simulator fidelity report, seed ${check.seed}`,
    '',
    `- Check: \`${check.fidelityCheckId}\``,
    `- Seed: ${check.seed}`,
    `- State: ${FIDELITY_STATE_LABELS[check.state]}`,
    `- Verdict: ${check.verdict ?? 'not yet decided'}`,
    `- Created: ${check.createdAt}`,
    `- Started: ${check.startedAt ?? '—'}`,
    `- Finished: ${check.finishedAt ?? '—'}`,
    `- Content digest: \`${check.simulator.contentDigest}\``,
    '',
    '## Counts',
    '',
    '| Total | Matched | Diverged | Cannot be reproduced | Pending |',
    '|---|---|---|---|---|',
    `| ${counts.total} | ${counts.matched} | ${counts.diverged} | ${counts.unreproducible} | ${counts.pending} |`,
    '',
    '## Environments',
    '',
    '| Side | Service | Source revision | Image digest |',
    '|---|---|---|---|',
    ...environmentRows('Simulator', check.simulator),
    ...environmentRows('Real lane', check.lane),
    '',
    '## Variants',
    '',
    '| Label | Effective rules digest | Overrides |',
    '|---|---|---|',
    ...check.variants.map((variant) => {
      const overrides = Object.entries(variant.overrides)
      return `| ${variant.label} | \`${variant.effectiveRulesDigest}\` | ${
        overrides.length === 0 ? 'none' : overrides.map(([key, value]) => `\`${key}\` = ${value}`).join('<br>')
      } |`
    }),
  ]
  if (check.failure) {
    lines.push('', '## Failure', '', `${check.failure.message} (\`${check.failure.code}\`)`)
  }
  if (unmatched.length > 0) {
    lines.push(
      '',
      '## Cases that did not match',
      '',
      '| Variant | Players | Faction set | Rotation | Policy | Result | Detail |',
      '|---|---|---|---|---|---|---|',
      ...unmatched.map(
        (item) =>
          `| ${item.variantLabel} | ${item.playerCount} | ${item.factionSet.join(' + ')} | ${item.seatRotation} | ${item.policyId} | ${FIDELITY_RESULT_LABELS[item.result]} | ${detailOf(item)} |`,
      ),
    )
  }
  return `${lines.join('\n')}\n`
}
