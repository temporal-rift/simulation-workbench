import { beforeEach, describe, expect, it } from 'vitest'
import { experimentTemplates, laneBundles, manifest } from '../test/fixtures'
import {
  BUNDLES_FIELD,
  applyTemplate,
  bundleFromLane,
  clearDraft,
  draftToManifest,
  emptyDraft,
  errorWithin,
  fingerprint,
  incomparableFields,
  hasUnsentDraft,
  loadDraft,
  localProblems,
  manifestToDraft,
  saveDraft,
  seedList,
} from './draft'

beforeEach(() => window.sessionStorage.clear())

describe('the draft and its manifest', () => {
  it('round-trips a manifest', () => {
    expect(draftToManifest(manifestToDraft(manifest()))).toEqual(manifest())
  })

  it('sorts player counts and reads seeds typed in any separator', () => {
    const draft = { ...emptyDraft(), seeds: '42, 43\n44  45', playerCounts: [5, 3] }

    expect(seedList(draft.seeds)).toEqual(['42', '43', '44', '45'])
    expect(draftToManifest(draft).playerCounts).toEqual([3, 5])
  })

  it('leaves incomplete numbers for the service to refuse, instead of inventing them', () => {
    const sent = draftToManifest({ ...emptyDraft(), concurrency: '', caseWallTimeoutSeconds: 'abc' })

    expect(sent.concurrency).toBeUndefined()
    expect(sent.caseWallTimeoutSeconds).toBeNaN()
  })

  it('derives each seat from its policy', () => {
    const draft = manifestToDraft(manifest())
    draft.policies[0]!.seats = ['1', '']

    expect(draftToManifest(draft).policies[0]!.seatAssignment).toEqual([
      { seatIndex: 1, policyId: 'random', policyVersion: 'v1' },
      { seatIndex: undefined, policyId: 'random', policyVersion: 'v1' },
    ])
  })

  it('finds the parameters that are not a JSON object before anything is sent', () => {
    const draft = manifestToDraft(manifest())
    draft.policies[0]!.parameters = '[1]'

    expect(localProblems(draft)).toEqual({ 'policies[0].parameters': 'Parameters must be a JSON object, for example {}.' })
    expect(localProblems(manifestToDraft(manifest()))).toEqual({})
  })

  it('asks for a bundle on every variant that has none', () => {
    const draft = manifestToDraft(manifest())
    draft.variants[1]!.bundle = null

    expect(localProblems(draft)).toEqual({ 'variants[1]': 'Choose the bundle this variant runs.' })
  })

  it('sends the selected bundles exactly, with the services and contracts they share', () => {
    const [baseline, candidate] = laneBundles()
    const draft = manifestToDraft(manifest())
    draft.variants[0]!.bundle = bundleFromLane(baseline!)
    draft.variants[1]!.bundle = bundleFromLane(candidate!)

    const sent = draftToManifest(draft)

    expect(sent.variants.map((variant) => [variant.effectiveRulesDigest, variant.effectiveContentDigest])).toEqual([
      [baseline!.effectiveRulesDigest, baseline!.effectiveContentDigest],
      [candidate!.effectiveRulesDigest, candidate!.effectiveContentDigest],
    ])
    expect(sent.variants[0]!.rulesArtifact).toEqual(baseline!.rulesArtifact)
    expect(sent.services).toEqual(baseline!.services)
    expect(sent.contracts).toEqual(baseline!.contracts)
  })

  it('names every field in which the selected bundles differ and blocks sending them', () => {
    const [baseline, candidate] = laneBundles()
    const draft = manifestToDraft(manifest())
    draft.variants[0]!.bundle = bundleFromLane(baseline!)
    draft.variants[1]!.bundle = bundleFromLane({
      ...candidate!,
      services: { ...candidate!.services, readService: { imageDigest: candidate!.services.readService.imageDigest, sourceRevision: 'fedcba9' } },
      contracts: { 'session-api': '3.1.0', 'action-api': '7.1.0' },
    })

    expect(incomparableFields(draft)).toEqual(['contracts.action-api', 'contracts.session-api', 'services.readService.sourceRevision'])
    expect(localProblems(draft)[BUNDLES_FIELD]).toBe(
      'The selected bundles are not comparable: they differ in contracts.action-api, contracts.session-api, services.readService.sourceRevision.',
    )
  })

  it('fills a template with one slot per variant, assigning distinct bundles while they last', () => {
    const template = experimentTemplates()[1]!
    const filled = applyTemplate({ ...emptyDraft(), name: 'mine' }, template, laneBundles().slice(0, 1))

    expect(filled.name).toBe('mine')
    expect(filled.variants.map((variant) => variant.label)).toEqual(['baseline', 'candidate'])
    expect(filled.variants[0]!.bundle?.effectiveRulesDigest).toBe(laneBundles()[0]!.effectiveRulesDigest)
    expect(filled.variants[1]!.bundle).toBeNull()
    expect(filled.seeds).toBe('42')
    expect(filled.playerCounts).toEqual([3, 4, 5])
    expect(filled.factionSets).toHaveLength(16)
    expect(filled.policies).toEqual([{ id: 'random', version: 'v1', artifactDigest: '9'.repeat(64), parameters: '{}', seats: ['0', '1', '2', '3', '4'] }])
    expect([filled.concurrency, filled.caseWallTimeoutSeconds, filled.maxRejectedCandidatesPerWindow]).toEqual(['3', '3600', '10'])
  })

  it('changes its fingerprint when the draft changes', () => {
    const before = fingerprint(draftToManifest(manifestToDraft(manifest())))
    const edited = manifestToDraft(manifest())
    edited.name = 'renamed'

    expect(fingerprint(draftToManifest(edited))).not.toBe(before)
  })
})

describe('error placement', () => {
  it('finds an error on a list or anything inside it', () => {
    const errors = { 'seeds[1]': 'duplicate', name: 'too long' }

    expect(errorWithin(errors, 'seeds')).toBe('duplicate')
    expect(errorWithin(errors, 'name')).toBe('too long')
    expect(errorWithin(errors, 'policies')).toBeUndefined()
    expect(errorWithin({ seedsOther: 'x' }, 'seeds')).toBeUndefined()
  })
})

describe('draft persistence', () => {
  it('keeps the unsent draft for the tab and clears it', () => {
    saveDraft({ draft: { ...emptyDraft(), name: 'half' }, basedOn: { experimentId: 'e', name: 'base' } })

    expect(hasUnsentDraft()).toBe(true)
    expect(loadDraft()).toEqual({ draft: { ...emptyDraft(), name: 'half' }, basedOn: { experimentId: 'e', name: 'base' } })
    clearDraft()
    expect(loadDraft()).toBeNull()
  })

  it('ignores storage it cannot read and fills what an older draft lacks', () => {
    window.sessionStorage.setItem('workbench.experiment-draft.v2', 'not json')
    expect(loadDraft()).toBeNull()

    window.sessionStorage.setItem('workbench.experiment-draft.v2', JSON.stringify({ draft: { name: 'old' } }))
    expect(loadDraft()?.draft).toEqual({ ...emptyDraft(), name: 'old' })
  })

  it('never stores a credential', () => {
    saveDraft({ draft: emptyDraft(), basedOn: null })

    expect(JSON.stringify(Object.entries(window.sessionStorage))).not.toMatch(/token|bearer/i)
  })
})
