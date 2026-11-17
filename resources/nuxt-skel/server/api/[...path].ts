// Forwards /api/** to Play when Nuxt's own server runs (an app that sets
// ssr: true and is built with `nuxt build`, PF-211). It serves the server-side
// fetches made while a page renders, which pass the browser's cookies on, and
// any request the browser sends to Nuxt's server directly.
//
// The address is runtime config, not a build-time constant: NUXT_PLAY_BACKEND_URL
// set where Nuxt's server starts overrides the default nuxt.config.ts resolved,
// so one build works wherever Play runs. The play launchers set it.
//
// Inert in a single-page app: under `pnpm dev`, nitro.devProxy answers /api
// first, and the static build that play dist and play bundle ship has no server.
export default defineEventHandler((event) =>
  proxyRequest(event, useRuntimeConfig(event).playBackendUrl + event.path)
)
