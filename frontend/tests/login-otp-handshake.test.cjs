// Regression tests for the two-request sign-in flow.
// Source checks complement, but do not replace, a live browser/DB/email test.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const project = path.resolve(__dirname, '../..');
const read = (file) => fs.readFileSync(path.join(project, file), 'utf8');
const frontendSource = read('frontend/app/login/LoginClient.tsx');
const authSource = read('backend/src/main/java/com/logiplatform/service/AuthService.java');
const mailSource = read('backend/src/main/java/com/logiplatform/service/MailService.java');
const apiSource = read('frontend/lib/api-client.ts');

const javascript = ts.transpileModule(apiSource, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const loaded = new Module('api-client.login-otp.js', module);
loaded.filename = path.join(project, 'frontend/lib/api-client.ts');
loaded.paths = Module._nodeModulePaths(path.join(project, 'frontend'));
loaded._compile(javascript, loaded.filename);
const { authApi, ApiError, setAccessToken } = loaded.exports;

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}
const challengeResponse = {
  accessToken: null, tenantId: 'tenant-1', userId: 'user-1', role: 'ADMIN',
  mustChangePassword: false, otpRequired: true, otpChallengeToken: 'signed-challenge',
};

test('password login returns a signed challenge before any email delivery is attempted', async () => {
  const calls = [];
  global.fetch = async (url, options) => {
    calls.push({ url: String(url), body: JSON.parse(options.body) });
    if (String(url).endsWith('/api/auth/login')) return json(challengeResponse);
    if (String(url).endsWith('/api/auth/send-login-otp')) return new Response(null, { status: 204 });
    throw new Error('Unexpected endpoint');
  };
  const login = await authApi.login('  Staff@AAL.COM ', 'secret');
  assert.equal(login.otpRequired, true);
  assert.equal(login.otpChallengeToken, 'signed-challenge');
  assert.equal(calls.length, 1, 'no email request until the OTP step is opened');
  await authApi.sendLoginOtp(login.otpChallengeToken);
  assert.equal(calls.length, 2);
  assert.match(calls[1].url, /\/api\/auth\/send-login-otp$/);
  assert.deepEqual(calls[1].body, { otpChallengeToken: 'signed-challenge' });
  assert.equal(calls[0].body.email, 'staff@aal.com');
});

test('an unconfirmed email delivery keeps the login challenge available', async () => {
  const calls = [];
  global.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).endsWith('/api/auth/login')) return json(challengeResponse);
    if (String(url).endsWith('/api/auth/send-login-otp')) {
      return json({ message: 'Provider did not confirm delivery' }, 504);
    }
    throw new Error('Unexpected endpoint');
  };
  const login = await authApi.login('staff@aal.com', 'secret');
  await assert.rejects(authApi.sendLoginOtp(login.otpChallengeToken), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 504);
    return true;
  });
  assert.deepEqual(calls.map((url) => new URL(url).pathname), [
    '/api/auth/login', '/api/auth/send-login-otp',
  ], 'a timed-out OTP send must not replay the credential request');
});

test('frontend must switch to the OTP screen before requesting an email', () => {
  const loginPart = frontendSource.slice(
    frontendSource.indexOf('if (step === "credentials")'),
    frontendSource.indexOf('await finish(r);'),
  );
  assert.ok(loginPart.indexOf('setStep("otp")') < loginPart.indexOf('void deliverCode('));
  assert.match(frontendSource, /err\.status === 408 \|\| err\.status === 504/);
  assert.match(frontendSource, /You do not need to sign in again/);
  assert.match(frontendSource, /sendingCode/);
});

test('backend only contacts the email provider in the OTP delivery endpoint', () => {
  const credentialLogin = authSource.slice(
    authSource.indexOf('public AuthResponse login('),
    authSource.indexOf('public void sendLoginOtp('),
  );
  const otpDelivery = authSource.slice(
    authSource.indexOf('public void sendLoginOtp('),
    authSource.indexOf('private String issueOtpIfNeeded('),
  );
  assert.doesNotMatch(credentialLogin, /mail\.sendLoginOtp\(/);
  assert.match(credentialLogin, /generateLoginOtpChallengeToken\(/);
  assert.match(otpDelivery, /mail\.sendLoginOtp\(/);
  assert.match(otpDelivery, /ex\.isDeliveryUncertain\(\)/);
  assert.match(otpDelivery, /HttpStatus\.GATEWAY_TIMEOUT/);
  assert.match(mailSource, /new MailDeliveryException\("Email provider did not confirm delivery", ex, true\)/);
});

test('public login/OTP 401 errors never invalidate an unrelated existing session', () => {
  assert.match(apiSource, /response\.status === 401 && shouldAttachAuthentication\(path\)/);
  assert.match(apiSource, /path === "\/api\/auth\/send-login-otp"\) return AUTH_OTP_DELIVERY_TIMEOUT_MS/);
});

test('a client-side OTP delivery timeout does not send a second login or OTP request', async () => {
  const calls = [];
  global.fetch = async (url) => {
    calls.push(new URL(String(url)).pathname);
    if (String(url).endsWith('/api/auth/login')) return json(challengeResponse);
    if (String(url).endsWith('/api/auth/send-login-otp')) {
      throw new DOMException('Request timed out', 'TimeoutError');
    }
    throw new Error('Unexpected endpoint');
  };
  const challenge = (await authApi.login('staff@aal.com', 'secret')).otpChallengeToken;
  await assert.rejects(authApi.sendLoginOtp(challenge), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 408);
    return true;
  });
  assert.deepEqual(calls, ['/api/auth/login', '/api/auth/send-login-otp']);
});

test('a rejected login cannot broadcast global session-expired', async () => {
  const events = [];
  global.window = {
    dispatchEvent: (event) => events.push(event.type),
    sessionStorage: {
      getItem: () => null,
      setItem: () => undefined,
      removeItem: () => undefined,
    },
  };
  setAccessToken('existing-session');
  global.fetch = async () => json({ message: 'Incorrect code' }, 401);
  try {
    await assert.rejects(
      authApi.login('staff@aal.com', 'secret', '123456', 'signed-challenge'),
      (error) => error instanceof ApiError && error.status === 401,
    );
    assert.deepEqual(events, []);
  } finally {
    delete global.window;
  }
});
