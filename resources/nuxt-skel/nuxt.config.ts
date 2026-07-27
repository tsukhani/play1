// https://nuxt.com/docs/api/configuration/nuxt-config
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// Which port the Play backend is on, in precedence order:
//
//   1. PLAY_BACKEND_PORT — set this when Play was started with a CLI override
//      (`play run --http.port=9001`), which never touches application.conf:
//          PLAY_BACKEND_PORT=9001 pnpm dev
//   2. http.port from this app's own conf/application.conf — so setting the port
//      there once keeps both halves of the app in agreement with no extra step.
//   3. 9000, Play's default.
//
// Deliberately NOT auto-detected by probing for a listening server: Play's
// default port is shared by every Play app on the machine, so probing can
// silently bind the frontend to a different application that happens to be
// running. A wrong-backend connection is much harder to diagnose than a refused
// one. Resolve the *configured* port; never guess the *running* one.
function portFromApplicationConf(): string | undefined {
  try {
    const confPath = fileURLToPath(new URL('../conf/application.conf', import.meta.url))
    // Unprefixed keys only — a `%prod.http.port` belongs to a different play.id
    // and must not override the port this dev server should talk to.
    return readFileSync(confPath, 'utf8').match(/^\s*http\.port\s*=\s*(\d+)/m)?.[1]
  } catch {
    // No conf file (frontend checked out on its own, say) — fall through.
    return undefined
  }
}

const backendPort = process.env.PLAY_BACKEND_PORT || portFromApplicationConf() || '9000'
const backendUrl = `http://localhost:${backendPort}`

export default defineNuxtConfig({
  modules: ['@nuxtjs/tailwindcss'],

  devtools: { enabled: true },

  // Surfaced to the app so pages can show the port they are actually talking to
  // instead of a hardcoded guess.
  runtimeConfig: {
    public: { backendPort }
  },

  // Proxy API requests to the Play backend during development
  nitro: {
    devProxy: {
      '/api': {
        target: `${backendUrl}/api`,
        changeOrigin: true
      }
    }
  },

  // Proxy API requests in production (SSR mode)
  routeRules: {
    '/api/**': { proxy: `${backendUrl}/api/**` }
  },

  compatibilityDate: '2025-01-01'
})
