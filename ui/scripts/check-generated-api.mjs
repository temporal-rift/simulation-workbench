import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// Fails when the committed generated client differs from what the pinned contracts produce: a
// contract bump without regeneration, or a hand edit to generated code. Run after generate:api.
const root = fileURLToPath(new URL('..', import.meta.url))

// A source archive or an image build has no git history to compare against; the CI checkout does.
try {
  execFileSync('git', ['rev-parse', '--is-inside-work-tree'], { cwd: root, stdio: 'ignore' })
} catch {
  console.log('Not a git checkout: skipping the generated API drift check.')
  process.exit(0)
}
// Only the working tree counts: regenerated files that are already staged are not drift.
const changed = execFileSync('git', ['status', '--porcelain', '--untracked-files=all', '--', 'src/api/generated'], {
  cwd: root,
  encoding: 'utf8',
})
  .split('\n')
  .filter((line) => line.length > 3 && line[1] !== ' ')

// The generator writes LF. On Windows, Git may check out CRLF and report every generated file as
// modified even though the generated content is unchanged. Compare unstaged tracked files while
// ignoring end-of-line whitespace; untracked generated files still count as drift.
const drift = changed.filter((line) => {
  if (line.startsWith('??')) return true
  const path = line.slice(3)
  try {
    execFileSync('git', ['diff', '--ignore-space-at-eol', '--quiet', '--', path], { cwd: root })
    return false
  } catch (error) {
    if (error && typeof error === 'object' && 'status' in error && error.status === 1) return true
    throw error
  }
})

if (drift.length > 0) {
  console.error('src/api/generated does not match the contract pinned in pom.xml:')
  console.error(drift.slice(0, 20).join('\n'))
  console.error('Run `npm run generate:api` and commit the result.')
  process.exit(1)
}
console.log('src/api/generated matches the pinned contracts.')
