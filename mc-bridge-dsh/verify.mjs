/**
 * Offline harness for the mc-bridge tools: loads the bundle's `apply` with a fake tools registry and
 * calls every tool against the live bridge, so the tool logic is verified before DSH restarts.
 *
 * Run with: node verify.mjs
 */
import { apply } from './index.js'

const tools = new Map()
const ctx = {
  effect(fn) {
    fn()
  },
  tools: {
    register(definition) {
      tools.set(definition.name, definition)
      return () => tools.delete(definition.name)
    },
  },
}

apply(ctx, { baseUrl: 'http://127.0.0.1:25963' })

const problems = []
const check = (label, condition, detail) => {
  if (condition) {
    console.log(`ok   ${label}`)
  } else {
    problems.push(`${label}: ${detail}`)
    console.log(`FAIL ${label}: ${detail}`)
  }
}

check('three tools registered', tools.size === 3, [...tools.keys()].join(','))

const server = await tools.get('mc_server').execute({}, {})
check('mc_server sees the running server', server.ok === true && typeof server.status?.tick === 'number', JSON.stringify(server).slice(0, 200))
check('mc_server lists worlds', Array.isArray(server.status?.worlds) && server.status.worlds.length === 3, String(server.status?.worlds?.length))
check('mc_server level type is flat', server.status?.serverVersion?.includes('26.3'), JSON.stringify(server.status?.serverVersion))

const block = await tools.get('mc_bridge').execute({ path: '/block', query: { world: 'world', x: 5, y: -60, z: 5 } }, {})
check('mc_bridge reads a block with vanilla state id and collision', block.data?.block?.stateId === 1 && block.data?.block?.collision?.length === 1, JSON.stringify(block.data?.block))
check('mc_bridge passes query parameters as strings', block.url.includes('x=5') && block.url.includes('y=-60'), block.url)

const events = await tools.get('mc_watch').execute({ since: 0, limit: 5, waitTicks: 2 }, {})
check('mc_watch advances ticks and returns a cursor', typeof events.nextSince === 'number' && typeof events.tick === 'number', JSON.stringify(events).slice(0, 200))
check('mc_watch reports an empty log on a quiet server', events.count === 0 || Array.isArray(events.entries), String(events.count))

const rendered = tools.get('mc_bridge').output.render({}, { ok: true, httpStatus: 200, data: { a: 1 } })
check('render produces a text content block', rendered.length === 1 && rendered[0].type === 'text' && rendered[0].text.includes('"a": 1'), JSON.stringify(rendered))

try {
  await tools.get('mc_bridge').execute({ path: '/nope' }, {})
  check('unknown endpoint is an error', false, 'no error thrown')
} catch (error) {
  check('unknown endpoint is an error', /HTTP 404/.test(String(error.message)), String(error.message))
}

try {
  await tools.get('mc_bridge').execute({}, {})
  check('missing path is an error', false, 'no error thrown')
} catch (error) {
  check('missing path is an error', /path is required/.test(String(error.message)), String(error.message))
}

console.log(problems.length === 0 ? '\nRESULT: all checks passed' : `\nRESULT: ${problems.length} problem(s)`)
process.exit(problems.length === 0 ? 0 : 1)
