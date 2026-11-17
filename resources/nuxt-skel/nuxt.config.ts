// https://nuxt.com/docs/api/configuration/nuxt-config
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'

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
  // A single-page app, rendered in the browser. play dist and play bundle ship
  // `nuxt generate`'s static output (public/spa), served at the site root by the
  // reverse proxy with /api going to Play; no Node server runs per request. With
  // ssr: true, generate would prerender each page at build time and freeze what
  // its fetches returned then: /api/status would show whichever backend answered
  // on the build machine, if any, and never ask the deployed one (PF-208).
  // Per-request SSR needs Nuxt's own server (`nuxt build`), deployed beside Play.
  ssr: false,

  // Tailwind CSS 4 through its own Vite plugin, not @nuxtjs/tailwindcss: that
  // module's Tailwind 4 support is experimental, and its Tailwind 3 path adds a
  // tailwindcss/nesting plugin that Nuxt 4.6 cannot load (NUXT_B7027, PF-208).
  css: ['~/assets/css/tailwind.css'],
  vite: {
    plugins: [tailwindcss()]
  },

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

  // Proxy API requests when Nuxt runs its own server (`nuxt build`, `nuxt
  // preview`). The static build that play dist and play bundle ship never uses
  // this: there the reverse proxy sends /api to Play.
  routeRules: {
    '/api/**': { proxy: `${backendUrl}/api/**` }
  },

  compatibilityDate: '2025-01-01'
})
