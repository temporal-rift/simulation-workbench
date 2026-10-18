import type { Band, EvaluatedLine, EvaluationVerdict, LineVerdict, ValidityReasonCode } from '../api/generated'
import { describeFactionSet, formatValue } from '../reports/metricView'

export const BOT_RESULTS_STATEMENT = 'Bot results say nothing about balance among human players.'

export const VERDICT_LABELS: Readonly<Record<EvaluationVerdict, string>> = {
  REJECTED: 'Rejected: a gate failed',
  CONFIRMED: 'Confirmed',
  UNCONFIRMED: 'Unconfirmed: nothing rejects it and nothing confirms it',
  INVALID: 'Invalid: the run cannot be evaluated as evidence',
}

export const LINE_VERDICT_LABELS: Readonly<Record<LineVerdict, string>> = {
  MEETS: 'Meets',
  FAILS: 'Fails',
  INCONCLUSIVE: 'Inconclusive',
}

export const VALIDITY_LABELS: Readonly<Record<ValidityReasonCode, string>> = {
  UNFINISHED_CASES: 'Unfinished cases',
  CONTRACT_MISMATCH: 'Contract mismatch',
  CONFIGURATION_DRIFT: 'Configuration drift',
  EXECUTION_FAILED_SHARE: 'Too many execution failures',
  ALL_PLAYERS_ABANDONED: 'A game ended with every player gone',
}

export type LineFilter = 'ALL' | 'FAILS' | 'INCONCLUSIVE'

export const LINE_FILTER_LABELS: Readonly<Record<LineFilter, string>> = {
  ALL: 'All lines',
  FAILS: 'Failing lines',
  INCONCLUSIVE: 'Inconclusive lines',
}

export function filterLines(lines: readonly EvaluatedLine[], filter: LineFilter): EvaluatedLine[] {
  return filter === 'ALL' ? [...lines] : lines.filter((line) => line.verdict === filter)
}

/** A two-sided band as an interval; a one-sided limit as its limit, with the scale deviations are measured in. */
export function describeBand(band: Band): string {
  if (band.lower !== null && band.upper !== null) {
    return `${formatValue(band.lower)} to ${formatValue(band.upper)}`
  }
  const limit = band.upper !== null ? `at most ${formatValue(band.upper)}` : `at least ${formatValue(band.lower)}`
  return band.scale === null ? limit : `${limit} (scale ${formatValue(band.scale)})`
}

/** Where a line was measured: the policy, the table size, the faction set and what the metric is split by. */
export function describeCohort(line: EvaluatedLine): string {
  const { dimensions } = line
  const split = [
    dimensions.faction ? dimensions.faction : null,
    dimensions.endReason ? dimensions.endReason : null,
    dimensions.quantile !== undefined && dimensions.quantile !== null ? `quantile ${dimensions.quantile}` : null,
    dimensions.failureCodes ? `codes ${dimensions.failureCodes.join(' + ')}` : null,
  ].filter(Boolean)
  const where = `${line.policyId} · ${line.playerCount} players · ${describeFactionSet(line.factionSet)}`
  return split.length > 0 ? `${where} · ${split.join(', ')}` : where
}
