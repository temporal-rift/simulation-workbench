import { readFileSync } from 'node:fs'
import { defineConfig } from '@hey-api/openapi-ts'

// The one pin of the contract is `simulation-api.version` in ../pom.xml; Maven unpacks that release's spec into
// target/dependency-specs, which is where the client is generated from. SIMULATION_API_SPEC overrides it.
function pinnedSpec(): string {
  const pom = readFileSync('../pom.xml', 'utf8')
  const version = /<simulation-api\.version>([^<]+)<\/simulation-api\.version>/.exec(pom)?.[1]
  if (!version) {
    throw new Error('simulation-api.version is not declared in ../pom.xml.')
  }
  return `../target/dependency-specs/simulation-api-${version}-jar/openapi/v1/simulation.yml`
}

export default defineConfig({
  input: process.env.SIMULATION_API_SPEC ?? pinnedSpec(),
  output: { path: 'src/api/generated' },
  plugins: [
    // The service serves this application, so requests are same-origin and go through the authenticated fetch.
    { name: '@hey-api/client-fetch', baseUrl: false },
    '@hey-api/typescript',
    // Java serializes offsets as `Z` or `+hh:mm`; both are valid date-times.
    { name: 'zod', dates: { offset: true } },
    // Responses are validated against the contract. Requests are not: the service is the authority on a draft's
    // problems and reports them per field, so a draft is never rejected before it is sent.
    { name: '@hey-api/sdk', validator: { request: false, response: 'zod' } },
  ],
})
