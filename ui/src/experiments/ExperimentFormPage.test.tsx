import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'
import { FakeService, problem } from '../test/fakeService'
import { experimentTemplates, laneBundles, manifest } from '../test/fixtures'
import { gridValues } from './parameterSpace'
import { renderApp } from '../test/renderApp'
import { clearDraft, loadDraft, manifestToDraft, saveDraft } from './draft'

const EXPERIMENTS = /^\/api\/v1\/experiments$/
const PREVIEWS = /^\/api\/v1\/experiment-previews$/

let service: FakeService

beforeEach(() => {
  service = new FakeService()
  saveDraft({ draft: manifestToDraft(manifest()), basedOn: null })
})

async function previewTheDraft(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Preview matrix' }))
  await screen.findByRole('heading', { name: 'Matrix preview' })
}

describe('overriding parameters of a variant', () => {
  const VARIANT_RULES = /^\/api\/v1\/variant-rules-previews$/
  const threshold = 'game.rules.win-score-threshold'

  async function addAnOverride(user: ReturnType<typeof userEvent.setup>) {
    const buttons = await screen.findAllByRole('button', { name: 'Add override' })
    await user.click(buttons[1]!)
    return screen.findByRole('combobox', { name: 'Value of parameter 1' })
  }

  it('offers the parameters of the space with their baseline, range and step, and previews the rules on every change', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)

    const value = await addAnOverride(user)

    expect(screen.getByRole('combobox', { name: 'Parameter 1' })).toHaveValue(threshold)
    expect(value).toHaveValue('20')
    expect(value).toHaveAccessibleDescription('Baseline 20, from 16 to 26 in steps of 1.')
    await user.clear(value)
    await user.type(value, '22')
    await waitFor(() => expect(screen.getByText('Rules digest of variant 2').nextElementSibling).not.toHaveTextContent('Checking'))
    const previews = service.requests.filter((request) => VARIANT_RULES.test(request.path))
    expect(previews.at(-1)?.body).toMatchObject({ parameterSpaceVersion: 1, overrides: { [threshold]: 22 } })
  })

  it('shows the digest of the rules a variant makes, and freezes the manifest carrying it', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    const value = await addAnOverride(user)
    await user.clear(value)
    await user.type(value, '22')
    const digest = await waitFor(() => {
      const shown = screen.getByText('Rules digest of variant 2').nextElementSibling!.textContent!
      expect(shown).toMatch(/^[a-f0-9]{64}$/)
      return shown
    })

    await previewTheDraft(user)
    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    await user.click(screen.getByRole('button', { name: 'Confirm and freeze' }))

    await screen.findByRole('heading', { name: 'threshold experiment' })
    const sent = service.requests.filter((request) => request.method === 'POST' && EXPERIMENTS.test(request.path)).at(-1)!.body as {
      variants: { effectiveRulesDigest: string; overrides?: Record<string, number>; parameterSpaceVersion?: number }[]
    }
    expect(sent.variants[1]).toMatchObject({ effectiveRulesDigest: digest, overrides: { [threshold]: 22 }, parameterSpaceVersion: 1 })
    expect(digest).not.toBe(manifest().variants[1]!.effectiveRulesDigest)
  })

  it('shows the refusal on the row of an out-of-range value, and keeps the matrix from being previewed', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    const value = await addAnOverride(user)

    await user.clear(value)
    await user.type(value, '27')

    await waitFor(() => expect(value).toHaveAttribute('aria-invalid', 'true'))
    expect(value).toHaveAccessibleDescription(/must be from 16 to 26: 27/)
    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))
    expect(await screen.findByText('Fix the highlighted fields before previewing the matrix.')).toBeInTheDocument()
    expect(service.count('POST', PREVIEWS)).toBe(0)
  })

  it('offers only values on the step grid and no parameter twice', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await addAnOverride(user)

    const list = document.querySelector('datalist')!
    expect([...list.querySelectorAll('option')].map((option) => option.getAttribute('value'))).toEqual(gridValues({ key: threshold, services: ['game-service'], baseline: 20, min: 16, max: 26, step: 1 }))
    const parameter = screen.getByRole('combobox', { name: 'Parameter 1' })
    await user.click(screen.getAllByRole('button', { name: 'Add override' })[1]!)
    const options = within(await screen.findByRole('combobox', { name: 'Parameter 2' })).getAllByRole('option')
    expect(options.map((option) => option.getAttribute('value'))).not.toContain((parameter as HTMLSelectElement).value)
  })

  it('removes an override and returns to the baseline digest', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await addAnOverride(user)

    await user.click(screen.getByRole('button', { name: 'Remove override 1 of variant 2' }))

    expect(screen.queryByRole('combobox', { name: 'Value of parameter 1' })).not.toBeInTheDocument()
    expect(screen.getByText('Rules digest of variant 2').nextElementSibling).toHaveTextContent(manifest().variants[1]!.effectiveRulesDigest)
  })
})

describe('configuring an experiment', () => {
  it('previews the matrix the service returns and freezes nothing', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)

    await previewTheDraft(user)

    expect(screen.getByText(/in total\. Nothing is frozen yet/)).toHaveTextContent('4 cases in total')
    expect(screen.getByText('7'.repeat(64))).toBeInTheDocument()
    const breakdown = screen.getByRole('table', { name: 'Cases per variant and player count' })
    expect(within(breakdown).getByRole('row', { name: /baseline 3 2/ })).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: /Case coordinates/ })).getByText('42')).toBeInTheDocument()
    expect(service.count('POST', EXPERIMENTS)).toBe(0)
  })

  it('creates one experiment when the freeze is confirmed twice', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await previewTheDraft(user)

    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    expect(screen.getByText(/exactly 4 cases with the digest/)).toBeInTheDocument()
    await user.dblClick(screen.getByRole('button', { name: 'Confirm and freeze' }))

    await screen.findByText('Frozen and read-only. To change the configuration, start a new draft from it; this experiment stays as it is.')
    const freezes = service.requests.filter((request) => request.method === 'POST' && EXPERIMENTS.test(request.path))
    expect(freezes.length).toBeGreaterThan(0)
    expect(new Set(freezes.map((request) => request.headers.get('Idempotency-Key'))).size).toBe(1)
    expect(service.experiments.size).toBe(2)
    expect(loadDraft()).toBeNull()
  })

  it('retries a freeze whose answer was lost under the same key, so it cannot freeze twice', async () => {
    const user = userEvent.setup()
    service.failOnce('POST', EXPERIMENTS, () => problem(503, 'UNAVAILABLE', 'try later'))
    await renderApp('/experiments/new', service)
    await previewTheDraft(user)

    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    await user.click(screen.getByRole('button', { name: 'Confirm and freeze' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    await user.click(screen.getByRole('button', { name: 'Confirm and freeze' }))

    await screen.findByRole('heading', { name: 'threshold experiment' })
    const keys = service.requests.filter((request) => request.method === 'POST' && EXPERIMENTS.test(request.path)).map((request) => request.headers.get('Idempotency-Key'))
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('shows freeze and preview unavailable, naming the missing scope, to a read-only designer', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service, { scopes: [READ_SCOPE] })

    const preview = await screen.findByRole('button', { name: 'Preview matrix' })
    const freeze = screen.getByRole('button', { name: 'Freeze this experiment…' })
    expect(preview).toHaveAttribute('aria-disabled', 'true')
    expect(freeze).toHaveAttribute('aria-disabled', 'true')
    expect(preview).toHaveAccessibleDescription(`Unavailable: requires the ${WRITE_SCOPE} scope.`)
    expect(freeze).toHaveAccessibleDescription(`Unavailable: requires the ${WRITE_SCOPE} scope.`)

    await user.click(preview)
    expect(service.count('POST', PREVIEWS)).toBe(0)
  })

  it('places a refusal on the field it names and in the summary', async () => {
    const user = userEvent.setup()
    service.override('POST', PREVIEWS, () => problem(400, 'INVALID_EXPERIMENT', 'seeds must be unique: 42', 'seeds[1]'))
    await renderApp('/experiments/new', service)

    await user.click(await screen.findByRole('button', { name: 'Preview matrix' }))

    const seeds = await screen.findByRole('textbox', { name: 'Seeds' })
    expect(seeds).toHaveAttribute('aria-invalid', 'true')
    expect(seeds).toHaveAccessibleDescription(/seeds must be unique: 42/)
    expect(screen.getByRole('alert')).toHaveTextContent('Could not preview the matrix: the experiment configuration was refused.')
  })

  it('keeps the unsent draft across a new session and says so', async () => {
    const user = userEvent.setup()
    saveDraft({ draft: { ...manifestToDraft(manifest()), name: '' }, basedOn: null })
    const first = await renderApp('/experiments/new', service)
    await user.type(await screen.findByRole('textbox', { name: 'Experiment name' }), 'half written')
    first.unmount()

    await renderApp('/experiments/new', service)

    expect(await screen.findByRole('textbox', { name: 'Experiment name' })).toHaveValue('half written')
    expect(screen.getByText('Your unsent draft was restored.')).toBeInTheDocument()
  })

  it('does not let an outdated preview be frozen', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await previewTheDraft(user)

    await user.type(screen.getByRole('textbox', { name: 'Experiment name' }), ' v2')

    expect(screen.getByText('The draft changed after this preview. Preview again before freezing.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Freeze this experiment…' })).toBeDisabled()
  })

  it('keeps invalid policy parameters from being sent', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    const parameters = await screen.findByRole('textbox', { name: 'Parameters (JSON object)' })
    await user.clear(parameters)
    await user.type(parameters, 'not json')

    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))

    expect(parameters).toHaveAttribute('aria-invalid', 'true')
    expect(await screen.findByText('Fix the highlighted fields before previewing the matrix.')).toBeInTheDocument()
    await waitFor(() => expect(service.count('POST', PREVIEWS)).toBe(0))
  })
})

describe('choosing bundles and templates', () => {
  beforeEach(() => clearDraft())

  async function startFromTemplate(user: ReturnType<typeof userEvent.setup>, name: string) {
    await user.click(await screen.findByRole('button', { name: `Start from ${name}` }))
  }

  it('fills the draft from a template with no digest to type', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)

    await startFromTemplate(user, 'Rules variant comparison')

    expect(screen.getAllByRole('group', { name: /^Variant \d$/ })).toHaveLength(2)
    expect(screen.getByRole('textbox', { name: 'Seeds' })).toHaveValue('42')
    for (const count of [3, 4, 5]) {
      expect(screen.getByRole('checkbox', { name: `${count} players` })).toBeChecked()
    }
    expect(screen.getAllByRole('group', { name: /^Faction set \d+/ })).toHaveLength(16)
    expect(screen.queryByRole('textbox', { name: /digest|revision|version|module/i })).toBeNull()
    expect(screen.getByRole('textbox', { name: 'Concurrency' })).toHaveValue('3')
  })

  it('sends exactly the selected bundles, as a hand-assembled manifest of the same values', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await startFromTemplate(user, 'Rules variant comparison')
    const [a, b] = laneBundles()
    const selects = screen.getAllByRole('combobox', { name: 'Bundle' })
    await user.selectOptions(selects[0]!, `${b!.effectiveRulesDigest}/${b!.effectiveContentDigest}`)
    await user.selectOptions(selects[0]!, `${a!.effectiveRulesDigest}/${a!.effectiveContentDigest}`)
    await user.selectOptions(selects[1]!, `${b!.effectiveRulesDigest}/${b!.effectiveContentDigest}`)
    await user.type(screen.getByRole('textbox', { name: 'Experiment name' }), 'threshold 20 against 22')

    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))
    await screen.findByRole('heading', { name: 'Matrix preview' })

    const sent = service.requests.find((request) => request.method === 'POST' && PREVIEWS.test(request.path))!.body
    const { defaults } = experimentTemplates()[1]!
    const variant = (label: string, bundle: typeof a) => ({
      label,
      rulesArtifact: bundle!.rulesArtifact,
      contentArtifact: bundle!.contentArtifact,
      effectiveRulesDigest: bundle!.effectiveRulesDigest,
      effectiveContentDigest: bundle!.effectiveContentDigest,
    })
    expect(sent).toEqual({
      schemaVersion: 1,
      name: 'threshold 20 against 22',
      variants: [variant('baseline', a), variant('candidate', b)],
      services: a!.services,
      contracts: a!.contracts,
      policies: defaults.policies,
      seeds: defaults.seeds,
      playerCounts: defaults.playerCounts,
      factionSets: defaults.factionSets,
      seatRotationMode: 'CYCLIC',
      timingMode: 'LOGICAL',
      concurrency: defaults.concurrency,
      caseWallTimeoutSeconds: defaults.caseWallTimeoutSeconds,
      maxRejectedCandidatesPerWindow: defaults.maxRejectedCandidatesPerWindow,
    })
  })

  it('names the contract in which the selected bundles differ and keeps freezing unavailable', async () => {
    const user = userEvent.setup()
    service.laneBundles[1]!.contracts = { 'session-api': '3.1.0' }
    await renderApp('/experiments/new', service)

    await startFromTemplate(user, 'Rules variant comparison')

    expect(screen.getByText(/The selected bundles are not comparable: they differ in contracts\.session-api\./)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))
    expect(await screen.findByText('Fix the highlighted fields before previewing the matrix.')).toBeInTheDocument()
    expect(service.count('POST', PREVIEWS)).toBe(0)
    expect(screen.getByRole('button', { name: 'Freeze this experiment…' })).toBeDisabled()
  })

  it('asks for a bundle when a template needs more variants than there are bundles', async () => {
    const user = userEvent.setup()
    service.laneBundles = service.laneBundles.slice(0, 1)
    await renderApp('/experiments/new', service)

    await startFromTemplate(user, 'Rules variant comparison')
    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))

    const [first, second] = screen.getAllByRole('combobox', { name: 'Bundle' })
    expect(first).toHaveDisplayValue(/threshold-20/)
    expect(second).toHaveValue('')
    expect(second).toHaveAccessibleDescription(/Choose the bundle this variant runs\./)
  })

  it('says no lane is configured, offers no digest field and keeps freezing unavailable', async () => {
    const user = userEvent.setup()
    service.laneBundles = []
    await renderApp('/experiments/new', service)

    expect(await screen.findByText(/No lane is configured, so there is no bundle to run a variant on\./)).toBeInTheDocument()
    await startFromTemplate(user, 'Baseline policy comparison')
    expect(screen.queryByRole('combobox', { name: 'Bundle' })).toBeNull()
    expect(screen.queryByRole('textbox', { name: /digest/i })).toBeNull()
    expect(screen.getByRole('button', { name: 'Freeze this experiment…' })).toBeDisabled()
    expect(screen.getByText('No lane is configured, so nothing frozen now could run.')).toBeInTheDocument()
  })

  it('names the missing read scope when the bundles or templates are refused', async () => {
    service.override('GET', /^\/api\/v1\/lane-bundles$/, () => problem(403, 'INSUFFICIENT_SCOPE', 'simulation:read is required'))
    service.override('GET', /^\/api\/v1\/experiment-templates$/, () => problem(403, 'INSUFFICIENT_SCOPE', 'simulation:read is required'))
    await renderApp('/experiments/new', service)

    expect(
      await screen.findByText(`You cannot load the bundles the lanes serve: your session lacks the ${READ_SCOPE} scope.`),
    ).toBeInTheDocument()
    expect(
      await screen.findByText(`You cannot load the experiment templates: your session lacks the ${READ_SCOPE} scope.`),
    ).toBeInTheDocument()
  })

  it('offers every lane bundle when there are more than one page of them', async () => {
    const base = laneBundles()[0]!
    service.laneBundles = Array.from({ length: 501 }, (_, index) => ({
      ...base,
      effectiveRulesDigest: index.toString(16).padStart(64, '0'),
    }))
    await renderApp('/experiments/new', service)

    const select = (await screen.findAllByRole('combobox', { name: 'Bundle' }))[0]!
    await waitFor(() => expect(within(select).getAllByRole('option')).toHaveLength(501 + 1))
    expect(service.count('GET', /^\/api\/v1\/lane-bundles$/)).toBe(2)
  })

  it('makes the designer choose again a restored bundle a lane now serves under other provenance', async () => {
    const user = userEvent.setup()
    service.laneBundles[1]!.contracts = { 'session-api': '3.1.0' }
    saveDraft({ draft: manifestToDraft(manifest()), basedOn: null })
    await renderApp('/experiments/new', service)

    const second = (await screen.findAllByRole('combobox', { name: 'Bundle' }))[1]!
    expect(second).toHaveDisplayValue(/\(not served by any configured lane\)/)
    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))
    expect(await screen.findByText('Fix the highlighted fields before previewing the matrix.')).toBeInTheDocument()
    expect(second).toHaveAccessibleDescription(/No configured lane serves this bundle as selected/)
    expect(service.count('POST', PREVIEWS)).toBe(0)

    const [, current] = laneBundles()
    await user.selectOptions(second, `${current!.effectiveRulesDigest}/${current!.effectiveContentDigest}`)

    expect(second).toHaveAccessibleDescription(expect.not.stringMatching(/No configured lane serves/))
  })

  it('marks the bundle of a frozen experiment that no lane serves any more', async () => {
    service.laneBundles = service.laneBundles.slice(0, 1)
    saveDraft({ draft: manifestToDraft(manifest()), basedOn: null })
    await renderApp('/experiments/new', service)

    const selects = await screen.findAllByRole('combobox', { name: 'Bundle' })
    expect(selects[1]).toHaveDisplayValue(/\(not served by any configured lane\)/)
  })
})
