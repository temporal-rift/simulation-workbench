import { execSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// Runs before `npm run dev` so a pull that changed dependencies never starts the dev server
// against a stale node_modules, however the dev server is launched. `npm ci` installs exactly
// what package-lock.json lists and never rewrites it; the stamp records which lockfile the
// current node_modules was installed from.
const root = fileURLToPath(new URL('..', import.meta.url))
const lockfile = new URL('../package-lock.json', import.meta.url)
const stamp = new URL('../node_modules/.package-lock.sha256', import.meta.url)

const lockHash = createHash('sha256').update(readFileSync(lockfile)).digest('hex')

if (existsSync(stamp) && readFileSync(stamp, 'utf8') === lockHash) {
  process.exit(0)
}

console.log('package-lock.json changed since the last install: running npm ci')
// On Windows a running dev server keeps node_modules files locked and npm ci fails with EPERM:
// stop it first.
execSync('npm ci', { cwd: root, stdio: 'inherit' })
writeFileSync(stamp, lockHash)
