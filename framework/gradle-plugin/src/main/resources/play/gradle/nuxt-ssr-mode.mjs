// PF-210: prints whether the Nuxt frontend in argv[2] renders on a server, as Nuxt
// itself resolves its config: TypeScript, values computed from the environment, .nuxtrc
// and layers all count. playFrontendSpa runs it with node after `pnpm install`.
//
// One JSON line on stdout: {"ssr": <resolved ssr is true>, "explicit": <some layer of the
// config sets ssr itself>}. Nuxt resolves an absent key to true; the fork packages Nuxt's
// server only for a config that asks for it (PF-209), so it needs both.
//
// @nuxt/kit is reached through the application's own nuxt, because pnpm does not hoist it
// to the frontend's root.
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const dir = process.argv[2]
const nuxtPackage = createRequire(join(dir, 'package.json')).resolve('nuxt/package.json')
const kit = createRequire(nuxtPackage).resolve('@nuxt/kit')
const { loadNuxtConfig } = await import(pathToFileURL(kit).href)
const config = await loadNuxtConfig({ cwd: dir })
const explicit = (config._layers ?? []).some((layer) => layer.config && Object.hasOwn(layer.config, 'ssr'))
process.stdout.write(JSON.stringify({ ssr: config.ssr === true, explicit }) + '\n')
