// PF-210: prints whether the Nuxt frontend in argv[2] renders on a server, as Nuxt
// itself resolves its config: TypeScript, values computed from the environment, .nuxtrc
// and layers all count. playFrontendSpa runs it with `pnpm exec node` after `pnpm install`.
//
// One JSON line on stdout: {"ssr": <resolved ssr is true>, "explicit": <some layer of the
// config sets ssr itself>, "node": <the Node range the built server needs, or null>}. Nuxt
// resolves an absent key to true; the fork packages Nuxt's server only for a config that asks
// for it (PF-209), so it needs both. The range is Nitro's engines.node, which an SSR build
// writes to play1.json for the launchers' check (PF-212).
//
// @nuxt/kit and Nitro are reached through the application's own nuxt, because pnpm does not
// hoist them to the frontend's root.
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const dir = process.argv[2]
const nuxtPackage = createRequire(join(dir, 'package.json')).resolve('nuxt/package.json')
const fromNuxt = createRequire(nuxtPackage)
const { loadNuxtConfig } = await import(pathToFileURL(fromNuxt.resolve('@nuxt/kit')).href)
const config = await loadNuxtConfig({ cwd: dir })
const explicit = (config._layers ?? []).some((layer) => layer.config && Object.hasOwn(layer.config, 'ssr'))

let node = null
for (const nitro of ['nitropack/package.json', 'nitro/package.json']) {
    try {
        node = JSON.parse(readFileSync(fromNuxt.resolve(nitro), 'utf8')).engines?.node ?? null
        break
    } catch {
        // Not this one: Nitro 3 is published as `nitro`.
    }
}
process.stdout.write(JSON.stringify({ ssr: config.ssr === true, explicit, node }) + '\n')
