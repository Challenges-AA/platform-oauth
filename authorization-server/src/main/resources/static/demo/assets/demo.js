const STORAGE_KEY = 'portage.pkce.pending';

function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A new public-client proof for every attempt: 256 bits each for verifier and state. */
export async function createPkce() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const state = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return {verifier, challenge: base64url(new Uint8Array(digest)), state};
}

export function authorizationUrl(configuration, proof, origin) {
  const callback = new URL(configuration.redirectUri);
  if (callback.origin !== origin || callback.pathname !== '/demo/callback' || callback.search || callback.hash) {
    throw new Error('Le callback de démonstration doit être sur la même adresse que cette page, avec le chemin /demo/callback. Vérifiez OAUTH_DEMO_REDIRECT_URI et utilisez 127.0.0.1 plutôt que localhost.');
  }
  const url = new URL('/oauth2/authorize', origin);
  url.search = new URLSearchParams({response_type: 'code', client_id: configuration.clientId,
    redirect_uri: callback.href, scope: configuration.scope, state: proof.state,
    code_challenge: proof.challenge, code_challenge_method: 'S256'}).toString();
  return url.href;
}

export function verifyCallback(url, pending, now = Date.now()) {
  const params = url.searchParams;
  if (!pending || !/^[A-Za-z0-9_-]{43}$/.test(pending.verifier || '')
      || !/^[A-Za-z0-9_-]{43}$/.test(pending.state || '')
      || !Number.isFinite(pending.createdAt) || now < pending.createdAt || now - pending.createdAt > 10 * 60_000) {
    throw new Error('Aucune demande récente n’a été préparée dans cet onglet. Démarrez une nouvelle autorisation.');
  }
  const expected = new URL(pending.redirectUri);
  if (expected.origin !== url.origin || expected.pathname !== url.pathname
      || params.getAll('state').length !== 1 || params.get('state') !== pending.state) {
    throw new Error('Le state ou le callback ne correspond pas à cette demande. Aucun code n’est affiché. Démarrez une nouvelle autorisation.');
  }
  if (params.has('error')) {
    throw new Error('Le serveur a refusé l’autorisation. Vérifiez la demande et recommencez.');
  }
  if (params.getAll('code').length !== 1 || !params.get('code')) {
    throw new Error('Le serveur n’a pas retourné de code d’autorisation unique. Recommencez.');
  }
  return {...pending, code: params.get('code')};
}

async function initialize() {
  const element = id => document.getElementById(id);
  const start = element('start');
  const status = element('status');
  const callbackUrl = new URL(window.location.href);
  const isCallback = callbackUrl.pathname === '/demo/callback';
  // Remove the authorization code from browser history before any asynchronous work.
  if (isCallback) window.history.replaceState(null, '', callbackUrl.pathname);
  const message = (text, error = false) => {
    status.textContent = text;
    status.classList.toggle('error', error);
  };
  let configuration;
  try {
    const response = await fetch('/demo/config', {credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      headers: {'Accept': 'application/json'}});
    if (!response.ok) throw new Error('Votre session a expiré. Revenez à l’accueil pour vous reconnecter.');
    configuration = await response.json();
    element('subject').textContent = '· ' + configuration.subject;
    element('client').textContent = configuration.clientId;
    element('redirect').textContent = configuration.redirectUri;
    if (!crypto.subtle || !crypto.getRandomValues) {
      throw new Error('La génération PKCE nécessite HTTPS ou une adresse locale de confiance comme http://127.0.0.1:9000.');
    }
    message('Votre session est prête. Lancez une nouvelle autorisation.');
    start.disabled = false;
  } catch (error) {
    message(error.message || 'Impossible de préparer la démonstration.', true);
    return;
  }

  if (isCallback) {
    try {
      const stored = sessionStorage.getItem(STORAGE_KEY);
      sessionStorage.removeItem(STORAGE_KEY);
      const result = verifyCallback(callbackUrl, stored ? JSON.parse(stored) : null);
      element('authorization-code').value = result.code;
      element('verifier').value = result.verifier;
      element('challenge').textContent = result.challenge;
      element('state').textContent = result.state;
      element('result-client').textContent = result.clientId;
      element('result-redirect').textContent = result.redirectUri;
      element('endpoint').textContent = window.location.origin + '/oauth2/token';
      element('result').hidden = false;
      element('panel-title').textContent = 'Autorisation reçue';
      message('Le state est vérifié. Copiez les valeurs ci-dessous dans Postman.');
      start.textContent = 'Démarrer une autre autorisation PKCE ↗';
    } catch (error) {
      message(error instanceof SyntaxError ? 'La demande locale est illisible. Démarrez une nouvelle autorisation.' : error.message, true);
    }
  }

  start.addEventListener('click', async () => {
    start.disabled = true;
    element('result').hidden = true;
    element('authorization-code').value = '';
    element('verifier').value = '';
    try {
      sessionStorage.removeItem(STORAGE_KEY);
      message('Génération d’un nouveau vérificateur, du challenge S256 et du state…');
      const proof = await createPkce();
      const url = authorizationUrl(configuration, proof, window.location.origin);
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({...proof, createdAt: Date.now(),
        clientId: configuration.clientId, redirectUri: configuration.redirectUri}));
      window.location.assign(url);
    } catch (error) {
      message(error.message || 'Le stockage de cet onglet est indisponible. Autorisez le stockage de session puis réessayez.', true);
      start.disabled = false;
    }
  });

  for (const button of document.querySelectorAll('[data-copy]')) {
    button.addEventListener('click', async () => {
      const field = element(button.dataset.copy);
      try {
        await navigator.clipboard.writeText(field.value);
        button.textContent = 'Copié';
        window.setTimeout(() => { button.textContent = 'Copier'; }, 1800);
      } catch {
        field.focus();
        field.select();
        button.textContent = 'Ctrl+C';
      }
    });
  }
}

if (typeof document !== 'undefined') initialize();
