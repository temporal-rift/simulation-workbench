/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const service = process.env.WORKBENCH_SERVICE_URL ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rolldownOptions: {
      output: {
        // Libraries change far less often than the application: separate chunks keep them cached across releases.
        codeSplitting: {
          groups: [
            { name: 'react', test: /node_modules[/\\](react|react-dom|scheduler)[/\\]/ },
            { name: 'tanstack', test: /node_modules[/\\]@tanstack[/\\]/ },
            { name: 'vendor', test: /node_modules/ },
          ],
        },
      },
    },
  },
  server: {
    // In production the service serves this application, so the browser talks to one origin; the dev server
    // mirrors that by forwarding the service's paths.
    proxy: {
      '/api/': service,
      '/config.js': service,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    css: false,
    testTimeout: 10_000,
  },
})
