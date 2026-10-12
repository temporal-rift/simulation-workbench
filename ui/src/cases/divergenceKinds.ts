/** What each published divergence kind means, in the designer's words. */
export const DIVERGENCE_KINDS: Readonly<Record<string, string>> = {
  OBSERVATION: 'What the bot observed at this step differs from the recording.',
  DECISION: 'The bot chose differently at this step.',
  WINDOW: 'The decision window opened at this step differs from the recording.',
  MISSING_STEP: 'The recording has a step that the reproduction never reached.',
  UNEXPECTED_STEP: 'The reproduction took a step that the recording does not have.',
  UNEXPECTED_WINDOW: 'The reproduction opened a decision window that the recording does not have.',
  COMMAND_REJECTED: 'The game rejected a command that the recording had accepted.',
  END_REASON: 'After the last step, the game ended for a different reason.',
  WINNERS: 'After the last step, the winners differ.',
  FINAL_SCORES: 'After the last step, the final scores differ.',
  ERAS: 'After the last step, the number of eras differs.',
  SEMANTIC_DIGEST: 'After the last step, the semantic digest of the whole game differs.',
}
