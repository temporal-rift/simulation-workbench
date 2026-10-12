import type { ConfidenceInterval, FactionSet, MetricDimensions, MetricStatus } from '../api/generated'

export const STATUS_LABELS: Readonly<Record<MetricStatus, string>> = {
  AVAILABLE: 'Available',
  INSUFFICIENT_SAMPLE: 'Insufficient sample: fewer than two independent blocks',
  NO_DATA: 'No data: the denominator is 0',
}

/** The dimensions a metric is restricted to, in words; an absent dimension means the metric is not split by it. */
export function describeDimensions(dimensions: MetricDimensions): string {
  const parts = Object.entries(dimensions)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => `${key}: ${String(value)}`)
  return parts.length > 0 ? parts.join(', ') : 'all'
}

export function formatValue(value: number | null): string {
  return value === null ? '—' : String(Number(value.toPrecision(6)))
}

/** An interval is shown only when the service returned one; its absence is never filled in. */
export function formatInterval(interval: ConfidenceInterval | null): string {
  return interval ? `${formatValue(interval.lower)} to ${formatValue(interval.upper)} (${Math.round(interval.level * 100)}%)` : 'No interval'
}

export function describeFactionSet(factionSet: FactionSet | null): string {
  return factionSet ? factionSet.join(', ') : 'all faction sets'
}

export function describeSeat(seatIndex: number | null): string {
  return seatIndex === null ? 'all seats' : `seat ${seatIndex}`
}
