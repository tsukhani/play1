// PF-212: how the play launchers run Nuxt's server beside Play. playFrontendSpa writes this
// file and play1.json next to server/ when it builds an SSR frontend, so they ship with it.
//
//   node play1-server.mjs --check   exit 1 with the reason when this Node does not meet the
//                                   built server's requirement (play1.json); exit 0 otherwise
//   node play1-server.mjs           the same check, then Nuxt's server (server/index.mjs)
//
// Stopping: when the file PLAY1_STOP_FILE names appears, it is removed and the server's own
// SIGTERM handler runs. Nitro then closes its listener, lets requests in flight finish and
// exits. A file and not a signal, because on Windows no signal reaches node.exe from another
// process, and one way to stop on every OS is one way to test. A SIGTERM from outside (systemd,
// docker stop) still works where the OS delivers one.
import { existsSync, readFileSync, rmSync } from 'node:fs'

const here = new URL('.', import.meta.url)
const required = JSON.parse(readFileSync(new URL('play1.json', here), 'utf8')).node

// Whether `version` (x.y.z) meets `range`: clauses joined by ||, each ^x.y.z, ~x.y.z, >=x.y.z,
// >x.y.z or x.y.z, which is what Nitro's engines.node uses. null for a form this does not read.
function satisfies(version, range) {
    const parse = (v) => v.trim().replace(/^v/, '').split('.').map((n) => Number.parseInt(n, 10) || 0)
    const cmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2]
    const have = parse(version)
    let readable = true
    const met = range.split('||').some((clause) => {
        const m = clause.trim().match(/^(\^|~|>=|>)?\s*(\d+(?:\.\d+){0,2})$/)
        if (!m) { readable = false; return false }
        const want = parse(m[2])
        switch (m[1]) {
            case '^': return have[0] === want[0] && cmp(have, want) >= 0
            case '~': return have[0] === want[0] && have[1] === want[1] && cmp(have, want) >= 0
            case '>=': return cmp(have, want) >= 0
            case '>': return cmp(have, want) > 0
            default: return cmp(have, want) === 0
        }
    })
    return met ? true : readable ? false : null
}

const met = required ? satisfies(process.versions.node, required) : true
if (met === false) {
    console.error(`play: Nuxt's server needs Node ${required}; this is Node ${process.versions.node} (${process.execPath})`)
    process.exit(1)
}
if (met === null) {
    console.error(`play: could not read Node requirement '${required}'; starting with Node ${process.versions.node} anyway`)
}
if (process.argv.includes('--check')) process.exit(0)

const stopFile = process.env.PLAY1_STOP_FILE
if (stopFile) {
    rmSync(stopFile, { force: true })
    setInterval(() => {
        if (!existsSync(stopFile)) return
        rmSync(stopFile, { force: true })
        process.emit('SIGTERM', 'SIGTERM')
    }, 100).unref()
}

await import(new URL('server/index.mjs', here).href)
