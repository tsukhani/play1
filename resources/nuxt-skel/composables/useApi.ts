import { appendResponseHeader } from 'h3'

/**
 * Composable for making API calls to the Play backend. All requests go to /api/,
 * which `pnpm dev`, Nuxt's server and the production reverse proxy forward to Play.
 *
 * When the page renders on the server (ssr: true), Nuxt passes the browser's
 * cookies on to Play but does not copy a cookie Play sets in its answer into the
 * page's response, so a session Play changes would be lost. This copies them
 * (Nuxt's documented pattern, PF-211). In the browser there is nothing to do:
 * the browser keeps the cookies of its own requests.
 */
export function useApi<T>(path: string) {
  const event = useRequestEvent()
  return useFetch<T>(`/api${path}`, {
    onResponse({ response }) {
      if (import.meta.server && event) {
        for (const cookie of response.headers.getSetCookie()) {
          appendResponseHeader(event, 'set-cookie', cookie)
        }
      }
    }
  })
}
