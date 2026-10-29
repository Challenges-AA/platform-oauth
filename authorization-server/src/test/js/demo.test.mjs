import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {webcrypto, createHash} from 'node:crypto';

if (!globalThis.crypto) globalThis.crypto = webcrypto;
const source = await readFile(new URL('../../main/resources/static/demo/assets/demo.js', import.meta.url), 'utf8');
const {createPkce, authorizationUrl, verifyCallback} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const origin = 'http://127.0.0.1:9000';
const configuration = {clientId: 'portage-public', scope: 'hello.read', redirectUri: origin + '/demo/callback'};
const pending = async () => ({...await createPkce(), ...configuration, createdAt: Date.now()});

test('generates fresh RFC 7636 verifiers and state with the correct SHA-256 challenge', async () => {
  const a = await createPkce();
  const b = await createPkce();
  for (const proof of [a, b]) {
    assert.match(proof.verifier, /^[A-Za-z0-9_-]{43}$/);
    assert.match(proof.state, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(proof.challenge, createHash('sha256').update(proof.verifier).digest('base64url'));
  }
  assert.notEqual(a.verifier, b.verifier);
  assert.notEqual(a.state, b.state);
});

test('authorization URL includes S256 and state but never the verifier', async () => {
  const proof = await createPkce();
  const url = new URL(authorizationUrl(configuration, proof, origin));
  assert.equal(url.pathname, '/oauth2/authorize');
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(url.searchParams.get('code_challenge'), proof.challenge);
  assert.equal(url.searchParams.get('state'), proof.state);
  assert.equal(url.searchParams.get('redirect_uri'), configuration.redirectUri);
  assert.equal(url.searchParams.get('client_id'), 'portage-public');
  assert.equal(url.searchParams.get('scope'), 'hello.read');
  assert.equal(url.searchParams.get('response_type'), 'code');
  assert.ok(!url.href.includes(proof.verifier));
});

test('rejects callback configuration on a foreign origin or an unsupported path', async () => {
  const proof = await createPkce();
  for (const redirectUri of ['https://example.org/demo/callback', origin + '/callback', origin + '/demo/callback?extra=1']) {
    assert.throws(() => authorizationUrl({...configuration, redirectUri}, proof, origin));
  }
});

test('accepts a matching callback and returns the original verifier', async () => {
  const saved = await pending();
  const url = new URL(configuration.redirectUri + '?code=example-code&state=' + saved.state);
  const result = verifyCallback(url, saved);
  assert.equal(result.code, 'example-code');
  assert.equal(result.verifier, saved.verifier);
});

test('rejects absent, forged, duplicated or expired state before exposing a code', async () => {
  const saved = await pending();
  const valid = new URL(configuration.redirectUri + '?code=example-code&state=' + saved.state);
  assert.throws(() => verifyCallback(valid, null));
  assert.throws(() => verifyCallback(valid, {...saved, createdAt: Date.now() - 11 * 60_000}));
  for (const query of ['code=x', 'code=x&state=forged', 'code=x&state=' + saved.state + '&state=' + saved.state,
    'code=x&code=y&state=' + saved.state, 'error=access_denied&state=' + saved.state]) {
    assert.throws(() => verifyCallback(new URL(configuration.redirectUri + '?' + query), saved));
  }
  assert.throws(() => verifyCallback(new URL('https://example.org/demo/callback' + valid.search), saved));
});
