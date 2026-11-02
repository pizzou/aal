const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');

// Keep the tests independent of a running API: exercise the same TypeScript
// module that the Next.js application imports, with controlled network errors.
const source = fs.readFileSync(path.resolve(__dirname, '../lib/api-client.ts'), 'utf8');
const javascript = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const compiled = new Module('api-client.js', module);
compiled.filename = path.resolve(__dirname, '../lib/api-client.ts');
compiled.paths = Module._nodeModulePaths(__dirname);
compiled._compile(javascript, compiled.filename);
const { apiFetch, setAccessToken, ApiError } = compiled.exports;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

test('simultaneous GET requests receive parsed JSON and only one fetch', async () => {
  setAccessToken('session-a');
  let calls = 0;
  global.fetch = async () => {
    calls += 1;
    await new Promise((resolve) => setTimeout(resolve, 20));
    return json({ content: [{ id: 'shipment-1' }], totalElements: 1 });
  };
  const [first, second] = await Promise.all([
    apiFetch('/api/shipments?coalesce-test'),
    apiFetch('/api/shipments?coalesce-test'),
  ]);
  assert.deepEqual(first, second);
  assert.deepEqual(first.content, [{ id: 'shipment-1' }]);
  assert.equal(calls, 1);
});

test('switching accounts clears cached data even for same endpoint', async () => {
  let calls = 0;
  global.fetch = async () => json({ version: ++calls });
  setAccessToken('session-b');
  assert.equal((await apiFetch('/api/shipments?account-switch')).version, 1);
  setAccessToken('session-c');
  assert.equal((await apiFetch('/api/shipments?account-switch')).version, 2);
  assert.equal(calls, 2);
});

test('timed-out GET requests do not automatically replay on a congested server', async () => {
  setAccessToken('session-d');
  let calls = 0;
  global.fetch = async () => {
    calls += 1;
    throw new DOMException('Request timed out', 'TimeoutError');
  };
  await assert.rejects(apiFetch('/api/shipments?timeout-test'), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 408);
    return true;
  });
  assert.equal(calls, 1);
});

test('financial mutations never retry on HTTP 503', async () => {
  setAccessToken('session-e');
  let attempts = 0;
  global.fetch = async (url) => {
    if (String(url).endsWith('/api/auth/csrf')) return json({ token: 'csrf-test' });
    attempts += 1;
    return json({ message: 'Service temporarily unavailable' }, 503);
  };
  await assert.rejects(apiFetch('/api/finance/test-payment', {
    method: 'POST', body: JSON.stringify({ amount: 100 }),
  }), (error) => error instanceof ApiError && error.status === 503);
  assert.equal(attempts, 1);
});
