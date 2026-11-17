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
  // A single-page app, rendered in the browser: play dist and play bundle ship
  // `nuxt generate`'s static output (public/spa), served at the site root by the
  // reverse proxy with /api going to Play. Set ssr: true to render pages on the
  // server instead: play dist and play bundle then build and ship Nuxt's server
  // (frontend/.output), and the play launchers run it beside Play, which needs
  // Node on the host (PF-209). Deleting the line is not the same: Nuxt's default
  // is ssr: true, but without the key the fork still runs `nuxt generate`, which
  // prerenders each page at build time and freezes what its fetches returned
  // then, /api/status included (PF-208).
  ssr: false,

  // Tailwind CSS 4 through its own Vite plugin, not @nuxtjs/tailwindcss: that
  // module's Tailwind 4 support is experimental, and its Tailwind 3 path adds a
  // tailwindcss/nesting plugin that Nuxt 4.6 cannot load (NUXT_B7027, PF-208).
  css: ['~/assets/css/tailwind.css'],
  vite: {
    plugins: [tailwindcss()]
  },

  devtools: { enabled: true },

  runtimeConfig: {
    // Where server/api/[...path].ts forwards /api when Nuxt's own server runs.
    // A runtime value: NUXT_PLAY_BACKEND_URL overrides it where that server
    // starts, so a build is not tied to the port Play had on the build machine.
    playBackendUrl: backendUrl,
    // Surfaced to the app so pages can show the port they are actually talking
    // to instead of a hardcoded guess.
    public: { backendPort }
  },

  // Proxy API requests to the Play backend during development. Under `pnpm dev`
  // this answers /api before server/api/[...path].ts does, SSE included.
  nitro: {
    devProxy: {
      '/api': {
        target: `${backendUrl}/api`,
        changeOrigin: true
      }
    }
  },

  compatibilityDate: '2025-01-01'
})
