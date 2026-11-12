import type { BooleanParameterDefinition, NumberParameterDefinition, ParameterDefinition, ParameterSpace } from '../api/generated'

/** A parameter on a numeric grid. */
export type NumericParameter = NumberParameterDefinition & { type: 'NUMBER' }

/** An on/off parameter: its only values are `false` and `true`. */
export type BooleanParameter = BooleanParameterDefinition & { type: 'BOOLEAN' }

export function isBooleanParameter(parameter: ParameterDefinition): parameter is BooleanParameter {
  return parameter.type === 'BOOLEAN'
}

/** The numeric parameters of the space, each on its grid. */
export function numericParameters(space: ParameterSpace): NumericParameter[] {
  return space.parameters.filter((parameter): parameter is NumericParameter => parameter.type === 'NUMBER')
}

function decimalPlaces(value: number): number {
  const text = String(value)
  const point = text.indexOf('.')
  return point < 0 ? 0 : text.length - point - 1
}

/**
 * Every value the parameter takes: from its minimum to its maximum in steps of its step. Computed on whole numbers
 * scaled by the finest decimal place, so steps of 0.25 do not drift.
 */
export function gridValues(parameter: NumberParameterDefinition): string[] {
  const scale = 10 ** Math.max(decimalPlaces(parameter.min), decimalPlaces(parameter.max), decimalPlaces(parameter.step))
  const step = Math.round(parameter.step * scale)
  if (step <= 0) {
    return []
  }
  const values: string[] = []
  for (let scaled = Math.round(parameter.min * scale); scaled <= Math.round(parameter.max * scale); scaled += step) {
    values.push(String(scaled / scale))
  }
  return values
}

/** The parameter as a designer weighs it: where it stands, and how far it may move and in what steps, or that it is a switch. */
export function describeParameter(parameter: ParameterDefinition): string {
  return isBooleanParameter(parameter)
    ? `Baseline ${parameter.baseline}, either false or true.`
    : `Baseline ${parameter.baseline}, from ${parameter.min} to ${parameter.max} in steps of ${parameter.step}.`
}

/** The value a new override of the parameter starts at: its baseline, as text for a number and as itself for a switch. */
export function initialOverrideValue(parameter: ParameterDefinition): string | boolean {
  return isBooleanParameter(parameter) ? parameter.baseline : String(parameter.baseline)
}

/** The newest space the workbench serves, the one a new override is chosen from. */
export function newestSpace(spaces: readonly ParameterSpace[]): ParameterSpace | null {
  return spaces.reduce<ParameterSpace | null>((newest, space) => (newest === null || space.version > newest.version ? space : newest), null)
}

/** The space a variant's overrides were chosen from, or the newest one for a variant that has none yet. */
export function spaceOf(spaces: readonly ParameterSpace[], version: number | null): ParameterSpace | null {
  return (version === null ? null : spaces.find((space) => space.version === version)) ?? newestSpace(spaces)
}
