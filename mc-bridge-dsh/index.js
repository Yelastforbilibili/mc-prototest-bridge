/**
 * Agent tools for the prototest-bridge HTTP plugin of a local Minecraft server.
 *
 * The Minecraft side (`prototest-bridge`, a Paper 26.3 plugin) is the authority: it answers with
 * vanilla registry data, real block states, collision boxes, live player state and a recorded event
 * log. This bundle turns that HTTP surface into three tools so an agent can check what the server
 * actually thinks instead of trusting a client-side model:
 *
 *  - `mc_bridge`  one raw request against any endpoint (the escape hatch; `/` lists them all)
 *  - `mc_server`  one consolidated snapshot: status, worlds, players, tick
 *  - `mc_watch`   poll the recorded event log for a bot's actions plus the current tick
 *
 * Nothing here talks to the Minecraft protocol: the bot under test connects to port 25565 normally,
 * and these tools only observe the server's own answer.
 */

export const name = 'mc-bridge'

export const inject = ['tools']

const DEFAULT_BASE_URL = 'http://127.0.0.1:25963'
const DEFAULT_TIMEOUT_MS = 20000
const MAX_TEXT_CHARS = 18000

function resolveConfig(config) {
  const raw = config ?? {}
  const baseUrl = typeof raw.baseUrl === 'string' && raw.baseUrl.length > 0 ? raw.baseUrl : DEFAULT_BASE_URL
  const token = typeof raw.token === 'string' && raw.token.length > 0 ? raw.token : ''
  const timeoutMs = Number.isFinite(raw.timeoutMs) && raw.timeoutMs > 0 ? Math.floor(raw.timeoutMs) : DEFAULT_TIMEOUT_MS
  return { baseUrl: baseUrl.replace(/\/+$/, ''), token, timeoutMs }
}

function buildUrl(cfg, path, query) {
  const suffix = typeof path === 'string' ? path : ''
  const url = new URL(cfg.baseUrl + (suffix.startsWith('/') ? suffix : '/' + suffix))
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null || value === '') continue
    url.searchParams.set(key, String(value))
  }
  return url
}

/**
 * Perform one bridge request and return the decoded JSON body.
 * The bridge always answers JSON, so a non-JSON body is reported with the raw text instead of
 * being swallowed by a parse error.
 */
async function callBridge(cfg, request) {
  const url = buildUrl(cfg, request.path, request.query)
  const method = request.method === 'POST' ? 'POST' : 'GET'
  const timeoutMs = Number.isFinite(request.timeoutMs) && request.timeoutMs > 0 ? Math.floor(request.timeoutMs) : cfg.timeoutMs
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const headers = { accept: 'application/json' }
  if (cfg.token.length > 0) headers['x-bridge-token'] = cfg.token
  let payload
  if (method === 'POST') {
    headers['content-type'] = 'application/json'
    payload = JSON.stringify(request.body ?? {})
  }
  try {
    const response = await fetch(url, { method, headers, body: payload, signal: controller.signal })
    const text = await response.text()
    let data
    let parsed = true
    try {
      data = text.length === 0 ? null : JSON.parse(text)
    } catch {
      parsed = false
      data = text
    }
    return {
      ok: response.ok,
      httpStatus: response.status,
      url: url.toString(),
      data,
      parsed,
      ...(parsed ? {} : { rawText: text.slice(0, MAX_TEXT_CHARS) }),
    }
  } catch (error) {
    const reason = error?.name === 'AbortError' ? `request timed out after ${timeoutMs} ms` : String(error?.message ?? error)
    throw new Error(`bridge request ${method} ${url.toString()} failed: ${reason}. Is the Minecraft server running with prototest-bridge enabled?`)
  } finally {
    clearTimeout(timer)
  }
}

function renderJson(value) {
  const text = JSON.stringify(value, null, 1) ?? 'null'
  return text.length > MAX_TEXT_CHARS ? text.slice(0, MAX_TEXT_CHARS) + `\n… truncated at ${MAX_TEXT_CHARS} chars` : text
}

function textBlock(text) {
  return [{ type: 'text', text }]
}

/** Argument validators: the tool schema rejects most mistakes, these catch the rest cheaply. */
function requirePath(args) {
  const path = args?.path
  if (typeof path !== 'string' || path.trim().length === 0) throw new Error('path is required, for example "/status"')
  return path.trim()
}

function optionalInt(value, name, min, max) {
  if (value === undefined || value === null) return undefined
  const n = Number(value)
  if (!Number.isFinite(n)) throw new Error(`${name} must be a number`)
  return Math.min(max, Math.max(min, Math.floor(n)))
}

export function apply(ctx, config) {
  const cfg = resolveConfig(config)

  ctx.effect(() =>
    ctx.tools.register({
      name: 'mc_bridge',
      description:
        'Send one request to the local Minecraft test server bridge (prototest-bridge, Paper 26.3) and get the ' +
        'server-authoritative JSON back. Use it for anything the three convenience tools do not cover: block and ' +
        'region state with vanilla state ids and collision boxes, the registry dump, player inventory, console ' +
        'commands, event-log control, movement traces, chunk and spawn handling. GET "/" on the bridge lists every ' +
        'endpoint. Actions taken through the bridge (setblock, fill, command, op) really happen on the server, so ' +
        'they change what the bot under test will see.',
      parameters: {
        type: 'object',
        additionalProperties: false,
        required: ['path'],
        properties: {
          path: {
            type: 'string',
            description: 'Bridge endpoint path, for example "/status", "/block", "/setblock", "/events", "/registry/blocks".',
          },
          method: {
            type: 'string',
            enum: ['GET', 'POST'],
            description: 'HTTP method; defaults to GET. Endpoints documented as POST require POST.',
          },
          query: {
            type: 'object',
            additionalProperties: true,
            description: 'Query parameters, for example {"world":"world","x":0,"y":64,"z":0}. Values are URL-encoded as strings.',
          },
          body: {
            type: 'object',
            additionalProperties: true,
            description: 'JSON request body for POST endpoints, for example {"world":"world","x":0,"y":64,"z":0,"block":"minecraft:stone"}.',
          },
          timeoutMs: {
            type: 'integer',
            description: 'Per-call timeout in milliseconds; defaults to the plugin config (20000).',
          },
        },
      },
      output: {
        schema: {
          description:
            'Bridge reply: httpStatus, the decoded JSON under data (server-specific shape), and url. When the ' +
            'server answered with something other than JSON, parsed is false and rawText carries the body.',
        },
        render: (_args, value) => textBlock(renderJson(value)),
      },
      async execute(args) {
        const path = requirePath(args)
        const method = args?.method === 'POST' ? 'POST' : 'GET'
        const reply = await callBridge(cfg, {
          path,
          method,
          query: args?.query,
          body: args?.body,
          timeoutMs: optionalInt(args?.timeoutMs, 'timeoutMs', 100, 120000),
        })
        if (!reply.ok) {
          const detail = typeof reply.data === 'object' && reply.data !== null ? JSON.stringify(reply.data) : String(reply.data)
          throw new Error(`bridge ${method} ${path} answered HTTP ${reply.httpStatus}: ${detail}`)
        }
        return reply
      },
    }),
  )

  ctx.effect(() =>
    ctx.tools.register({
      name: 'mc_server',
      description:
        'Snapshot of the local Minecraft test server the bot connects to: version, tick count, TPS, online ' +
        'players with position/health/game mode, and each world with spawn, time and gamerules. Start here to ' +
        'confirm the server is up and to see where the bot actually is.',
      parameters: {
        type: 'object',
        additionalProperties: false,
        properties: {
          players: {
            type: 'boolean',
            description: 'Include the full per-player detail (feet/eye position, health, food, pose, held item). Defaults to true.',
          },
        },
      },
      output: {
        schema: {
          description: 'Status fields plus worlds and players, exactly as the bridge answered.',
        },
        render: (_args, value) => textBlock(renderJson(value)),
      },
      async execute(args) {
        const detailed = args?.players !== false
        const status = await callBridge(cfg, { path: '/status' })
        if (!status.ok) throw new Error(`bridge /status answered HTTP ${status.httpStatus}: ${renderJson(status.data)}`)
        const result = { ok: true, status: status.data }
        if (detailed) {
          const players = await callBridge(cfg, { path: '/players' })
          result.players = players.ok ? players.data : { error: `HTTP ${players.httpStatus}`, data: players.data }
        }
        return result
      },
    }),
  )

  ctx.effect(() =>
    ctx.tools.register({
      name: 'mc_watch',
      description:
        'Read the server-side event log: every join, move, swing, interact, attack, block break/place, item use, ' +
        'chat, command, container click, death and respawn the server recorded, with a monotonic seq cursor. ' +
        'Call it with the previous cursor to see only what happened since. This is how you check whether a bot ' +
        'action actually reached the server, as opposed to only changing the bot\'s local model. Optionally let ' +
        'the server advance N ticks first so a pending action has time to land.',
      parameters: {
        type: 'object',
        additionalProperties: false,
        properties: {
          since: {
            type: 'integer',
            description: 'Return events with seq greater than this cursor. Omit or 0 for everything still buffered.',
          },
          type: {
            type: 'string',
            description: 'Only events of one recorded type, for example "break", "place", "interact", "swing", "join", "chat".',
          },
          limit: {
            type: 'integer',
            description: 'Maximum events to return; defaults to 200.',
          },
          waitTicks: {
            type: 'integer',
            description: 'Advance this many server ticks before reading the log, so a just-sent action can reach the server. 0 disables the wait.',
          },
        },
      },
      output: {
        schema: {
          description: 'Recorded events plus the cursor to pass as since next time, and status.tick after the optional wait.',
        },
        render: (_args, value) => textBlock(renderJson(value)),
      },
      async execute(args) {
        const waitTicks = optionalInt(args?.waitTicks, 'waitTicks', 0, 200)
        if (waitTicks !== undefined && waitTicks > 0) {
          const waited = await callBridge(cfg, { path: '/tick/wait', method: 'POST', body: { ticks: waitTicks } })
          if (!waited.ok) throw new Error(`bridge /tick/wait answered HTTP ${waited.httpStatus}: ${renderJson(waited.data)}`)
        }
        const query = {}
        const since = optionalInt(args?.since, 'since', 0, Number.MAX_SAFE_INTEGER)
        if (since !== undefined) query.since = since
        if (typeof args?.type === 'string' && args.type.length > 0) query.type = args.type
        const limit = optionalInt(args?.limit, 'limit', 1, 5000)
        if (limit !== undefined) query.limit = limit
        const events = await callBridge(cfg, { path: '/events', query })
        if (!events.ok) throw new Error(`bridge /events answered HTTP ${events.httpStatus}: ${renderJson(events.data)}`)
        const data = events.data ?? {}
        const list = Array.isArray(data.entries) ? data.entries : []
        const nextSince = typeof data.next === 'number' ? data.next : since ?? 0
        const status = await callBridge(cfg, { path: '/status' })
        return {
          ok: true,
          tick: status.ok && status.data ? status.data.tick : null,
          count: list.length,
          nextSince,
          entries: list,
        }
      },
    }),
  )
}
