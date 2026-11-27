import type { Campaign, HuntClassification, HuntReport } from '../api/generated'

/** What each class of a hunt cell means, in the words the report states it. */
const HUNT_CLASS_LABELS: Record<HuntClassification, string> = {
  NOT_CONFIRMED: 'Not confirmed',
  BALANCE_NOISE: 'Balance noise',
  FLAGGED: 'Flagged',
  EXPLOIT: 'Exploit',
}

/** The factor of the normal ratio from which a confirmed cell is flagged. */
const FLAG_FACTOR = 1.25

/** The factor of the normal ratio from which a confirmed cell is an exploit. */
const EXPLOIT_FACTOR = 1.5

const ratio = (value: number) => value.toFixed(2)

/**
 * The result of a policy hunt: for one faction at one table size, what the search measured and, once the best
 * candidate was confirmed on fresh seeds, its ratio against the standard seat on the same seeds. A cell without a flag
 * is evidence and not a guarantee, which the report says.
 */
export function HuntReportSection({ campaign }: { readonly campaign: Campaign }) {
  const report = campaign.huntReport ?? null
  if (campaign.kind !== 'POLICY_HUNT') {
    return null
  }
  return (
    <section aria-labelledby="campaign-hunt">
      <h2 id="campaign-hunt">Policy hunt</h2>
      <p>
        Searches the parameters of one {campaign.faction ?? 'faction'} seat of the faction-search policy at {campaign.playerCounts.join(', ')} players, against faction-spread seats.
        The objective is that seat&apos;s win ratio, its win rate over the fair share of one in the player count.
      </p>
      {report === null ? <p role="status">{pending(campaign)}</p> : <Cell report={report} />}
    </section>
  )
}

function pending(campaign: Campaign): string {
  switch (campaign.state) {
    case 'CONFIRMING':
      return 'Confirming the best candidate and the standard seat on fresh seeds.'
    case 'QUEUED':
    case 'RUNNING':
    case 'PAUSED':
      return 'Searching: the report appears when the search ends.'
    default:
      return 'The campaign ended without a report.'
  }
}

function Cell({ report }: { readonly report: HuntReport }) {
  const { screening, confirmation } = report
  return (
    <>
      <p>
        <strong>{HUNT_CLASS_LABELS[report.classification]}</strong>
        {report.classification === 'NOT_CONFIRMED'
          ? ': the best candidate did not reach the flag line in screening, or its confirmation has not run.'
          : null}
      </p>
      <h3>Screening</h3>
      <ul>
        <li>{screening.candidates} candidates screened in the first round.</li>
        <li>Win ratio of the best candidate in the last round: {ratio(screening.screeningRatioOfBest)}.</li>
        <li>Realized screening noise (standard deviation of the first-round ratios): {ratio(screening.noise)}.</li>
        <li>Share of candidates at or above the flag line: {(screening.shareAboveFlagLine * 100).toFixed(1)}%.</li>
      </ul>
      {confirmation === null ? null : (
        <>
          <h3>Confirmation</h3>
          <ul>
            <li>
              Candidate win ratio {ratio(confirmation.candidateRatio)} (interval {ratio(confirmation.candidateInterval.lower)} to{' '}
              {ratio(confirmation.candidateInterval.upper)}).
            </li>
            <li>Standard seat on the same seeds: {ratio(confirmation.standardRatio)}.</li>
            <li>
              Normal ratio {ratio(confirmation.normalRatio)}: flagged from {ratio(FLAG_FACTOR * confirmation.normalRatio)}, exploit from{' '}
              {ratio(EXPLOIT_FACTOR * confirmation.normalRatio)}. A ratio below the flag line is balance noise.
            </li>
          </ul>
          <h3>Parameters found</h3>
          {Object.keys(confirmation.parameters).length === 0 ? (
            <p>The best candidate plays the defaults.</p>
          ) : (
            <ul>
              {Object.entries(confirmation.parameters).map(([key, value]) => (
                <li key={key}>
                  <code>{key}</code> = {value}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      <ul>
        {report.limitations.map((limitation) => (
          <li key={limitation}>{limitation}</li>
        ))}
      </ul>
    </>
  )
}
