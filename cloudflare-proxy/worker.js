const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

function json(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function num(v, fallback) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : fallback;
}

async function underLimit(kv, key, limit) {
  if (!kv) return true;
  const cur = num(await kv.get(key), 0);
  return cur < limit;
}

async function bump(kv, key, ttlSeconds) {
  if (!kv) return;
  const cur = num(await kv.get(key), 0);
  await kv.put(key, String(cur + 1), { expirationTtl: ttlSeconds });
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return json(405, { error: { message: "Use POST." } });
    }

    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const day = new Date().toISOString().slice(0, 10);
    const minute = Math.floor(Date.now() / 60000);

    const IP_PER_MIN = num(env.IP_PER_MIN, 5);
    const IP_PER_DAY = num(env.IP_PER_DAY, 10);
    const GLOBAL_PER_DAY = num(env.GLOBAL_PER_DAY, 500);
    const MAX_TOKENS = num(env.MAX_TOKENS, 4096);
    const ALLOWED_MODELS = (env.ALLOWED_MODELS || "claude-opus-4-8")
      .split(",").map((s) => s.trim()).filter(Boolean);

    const kv = env.RL;
    const minKey = `m:${ip}:${minute}`;
    const ipDayKey = `d:${ip}:${day}`;
    const globalKey = `g:${day}`;

    if (!(await underLimit(kv, minKey, IP_PER_MIN))) {
      return json(429, { error: { type: "rate_limit_minute", message: "Too many requests this minute (limit: " + IP_PER_MIN + "/min). Wait a moment and try again." } });
    }
    if (!(await underLimit(kv, ipDayKey, IP_PER_DAY))) {
      return json(429, { error: { type: "rate_limit_day", message: "You've used all " + IP_PER_DAY + " of your free requests for today. Try again tomorrow." } });
    }
    if (!(await underLimit(kv, globalKey, GLOBAL_PER_DAY))) {
      return json(503, { error: { type: "rate_limit_global", message: "The shared daily budget (" + GLOBAL_PER_DAY + " requests) is used up. Try again tomorrow." } });
    }

    const MAX_BODY_BYTES = num(env.MAX_BODY_BYTES, 60000);
    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) {
      return json(413, { error: { message: "Request too large." } });
    }
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      return json(400, { error: { message: "Invalid JSON body." } });
    }
    if (body.model && !ALLOWED_MODELS.includes(body.model)) {
      return json(400, { error: { message: `Model not allowed.` } });
    }
    if (!body.model) body.model = ALLOWED_MODELS[0];
    if (!body.max_tokens || body.max_tokens > MAX_TOKENS) body.max_tokens = MAX_TOKENS;

    const upstream = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify(body),
    });

    if (upstream.ok) {
      await bump(kv, minKey, 120);
      await bump(kv, ipDayKey, 90000);
      await bump(kv, globalKey, 90000);
    }

    const text = await upstream.text();
    return new Response(text, {
      status: upstream.status,
      headers: { "content-type": "application/json" },
    });
  },
};
