"""Démarre les trois services empaquetés et vérifie le flux OAuth sans afficher de secrets."""
import argparse, base64, json, os, pathlib, re, secrets, socket, subprocess, sys, tempfile, time
import urllib.error, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
STAGE = 'preflight'

def request(url, data=None, headers=None):
    """Effectue un appel HTTP et retourne aussi le statut des réponses d'erreur."""
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}), timeout=8) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def require(condition, label):
    """Interrompt la démo avec un diagnostic synthétique si une assertion échoue."""
    if not condition: raise RuntimeError(label)

def basic(user, secret):
    """Construit l'en-tête HTTP Basic utilisé pour le client et les rôles internes."""
    return 'Basic ' + base64.b64encode((user + ':' + secret).encode()).decode()

def header(token):
    """Décode l'en-tête JWT pour vérifier notamment le kid, sans valider la signature."""
    return json.loads(base64.urlsafe_b64decode(token.split('.')[0] + '=='))

def ready(url, process, expected):
    """Attend l'état HTTP attendu et détecte un processus arrêté ou un délai dépassé."""
    until = time.monotonic() + 60
    while time.monotonic() < until:
        if process.poll() is not None: raise RuntimeError('A service exited before readiness')
        try:
            if request(url)[0] == expected: return
        except (OSError, urllib.error.URLError): pass
        time.sleep(.25)
    raise RuntimeError('Readiness timeout')

def stop(process):
    """Arrête un service lancé par la démo et force l'arrêt après le délai prévu."""
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)

def main():
    global STAGE
    parser = argparse.ArgumentParser()
    parser.add_argument('--skip-build', action='store_true', help='Use existing packaged JARs')
    parser.add_argument('--skip-newman', action='store_true', help='Run the Python HTTP checks only')
    args = parser.parse_args()
    # Lance directement la JVM, pas le redirecteur Windows Oracle, pour maîtriser son cycle de vie.
    java_info = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
    java_home = re.search(r'^\s*java.home\s*=\s*(.+)$', java_info.stderr, re.MULTILINE)
    require(java_home is not None, 'Cannot locate the Java runtime')
    java = pathlib.Path(java_home.group(1).strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    # Refuse de perturber un service déjà lancé sur les ports réservés à cette démo.
    for port in (9000, 9001, 9002):
        with socket.socket() as s: s.bind(('127.0.0.1', port))
    env = os.environ.copy()
    for i in range(1, 6): env[f'OAUTH_USER{i}_PASSWORD'] = secrets.token_urlsafe(24)
    env['OAUTH_CLIENT_SECRET'] = secrets.token_urlsafe(32)
    env['KEY_SERVICE_SIGNING_SECRET'] = secrets.token_urlsafe(32)
    env['KEY_SERVICE_ADMIN_SECRET'] = secrets.token_urlsafe(32)
    env['KEY_SERVICE_MASTER_KEY'] = base64.b64encode(secrets.token_bytes(32)).decode()
    env['KEY_SERVICE_URL'] = 'http://127.0.0.1:9002'
    env['RESOURCE_SERVER_JWKS_URI'] = 'http://127.0.0.1:9002/oauth2/jwks'
    work = ROOT / '.work'; work.mkdir(exist_ok=True)
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='three-service-demo-', dir=work) as temporary:
        env['KEY_SERVICE_DIRECTORY'] = str(pathlib.Path(temporary) / 'keys')
        def start(module, suffix=''):
            """Lance un JAR avec uniquement les secrets dont ce service a besoin."""
            jar = ROOT / module / 'target' / (module + '-1.0.0-SNAPSHOT' + suffix + '.jar')
            require(jar.exists(), 'Missing packaged JAR for ' + module)
            log = open(pathlib.Path(temporary) / (module + '-' + str(len(logs)) + '.log'), 'w')
            logs.append(log)
            service_env = env.copy()
            if module != 'key-service':
                for name in ('KEY_SERVICE_MASTER_KEY', 'KEY_SERVICE_ADMIN_SECRET', 'KEY_SERVICE_DIRECTORY'):
                    service_env.pop(name, None)
            if module != 'authorization-server':
                for name in list(service_env):
                    if name.startswith('OAUTH_'): service_env.pop(name)
            if module == 'resource-server': service_env.pop('KEY_SERVICE_SIGNING_SECRET', None)
            process = subprocess.Popen([str(java), '-jar', str(jar)], cwd=ROOT, env=service_env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            return process
        try:
            if not args.skip_build:
                STAGE = 'maven-verify'
                subprocess.run([sys.executable, str(ROOT / 'scripts/verify.py')], cwd=ROOT, env=env, check=True)
            STAGE = 'start-services'
            keys = start('key-service', '-exec'); ready(env['KEY_SERVICE_URL'] + '/oauth2/jwks', keys, 200)
            auth = start('authorization-server'); ready('http://127.0.0.1:9000/oauth2/jwks', auth, 200)
            resource = start('resource-server'); ready('http://127.0.0.1:9001/hello', resource, 401)
            def token(subject='subject-1'):
                """Demande un jeton OAuth en authentifiant client et utilisateur."""
                form = urllib.parse.urlencode(dict(grant_type='urn:portage:params:oauth:grant-type:subject',
                    username='user1', password=env['OAUTH_USER1_PASSWORD'], subject=subject, scope='hello.read')).encode()
                return request('http://127.0.0.1:9000/oauth2/token', form,
                    {'Authorization': basic('demo-client', env['OAUTH_CLIENT_SECRET']), 'Content-Type': 'application/x-www-form-urlencoded'})
            def issued():
                """Exige une émission valide et retourne le jeton pour les assertions suivantes."""
                status, body = token(); require(status == 200, 'Token issuance failed')
                return json.loads(body)['access_token']
            def hello(access):
                """Appelle la ressource protégée avec le Bearer fourni."""
                return request('http://127.0.0.1:9001/hello', headers={'Authorization': 'Bearer ' + access})[0]
            # Vérifie l'émission, l'accès avec les deux générations de clés et les rôles.
            STAGE = 'issuance-and-rotation'
            first = issued(); require(hello(first) == 200, 'First token rejected')
            require(request(env['KEY_SERVICE_URL'] + '/internal/rotate', b'{}')[0] == 401, 'Unprotected rotation')
            require(request(env['KEY_SERVICE_URL'] + '/internal/rotate', b'{}',
                {'Authorization': basic('signer', env['KEY_SERVICE_SIGNING_SECRET']), 'Content-Type': 'application/json'})[0] == 403,
                'Signer could rotate keys')
            require(request(env['KEY_SERVICE_URL'] + '/internal/rotate', b'{}',
                {'Authorization': basic('admin', env['KEY_SERVICE_ADMIN_SECRET']), 'Content-Type': 'application/json'})[0] == 200,
                'Rotation failed')
            second = issued()
            require(header(first)['kid'] != header(second)['kid'], 'Rotation did not change signing key')
            require(hello(first) == 200 and hello(second) == 200, 'Rotation invalidated a valid token')
            public = json.loads(request(env['KEY_SERVICE_URL'] + '/oauth2/jwks')[1])
            require(len(public['keys']) == 2 and all('d' not in key for key in public['keys']), 'Invalid public JWKS')
            require(json.loads(request('http://127.0.0.1:9000/oauth2/jwks')[1]) == public, 'Issuer JWKS proxy mismatch')
            parts = second.split('.'); changed = dict(header(second), kid='unknown-key')
            parts[0] = base64.urlsafe_b64encode(json.dumps(changed).encode()).decode().rstrip('=')
            require(hello('.'.join(parts)) == 401, 'Unknown key accepted')
            require(token('subject-2')[0] == 400, 'Mismatched subject accepted')
            # Vérifie le 503 en signature, la vérification hors ligne via le JWKS en cache
            # puis la reprise avec le même kid après redémarrage du Key Service.
            STAGE = 'outage-and-restart'
            stop(keys)
            require(token()[0] == 503, 'Signing outage did not return 503')
            require(hello(second) == 200, 'Cached public key verification required signing service')
            keys = start('key-service', '-exec'); ready(env['KEY_SERVICE_URL'] + '/oauth2/jwks', keys, 200)
            third = issued()
            require(header(third)['kid'] == header(second)['kid'], 'Active key not restored after restart')
            require(hello(first) == 200 and hello(third) == 200, 'Restart broke token verification')
            if not args.skip_newman:
                STAGE = 'newman'
                subprocess.run(['node', str(ROOT / 'scripts/run-newman.cjs')], cwd=ROOT, env=env, check=True)
            STAGE = 'completed'
            print('Passed: three services, issuance, rotation, old/new signatures, access controls, public JWKS, outage and restart.')
        finally:
            # Les processus sont arrêtés dans l'ordre inverse même si une assertion échoue.
            for process in reversed(processes): stop(process)
            for log in logs: log.close()
    return 0

if __name__ == '__main__':
    # Garde un résultat assaini même en cas d'échec pour faciliter le diagnostic.
    try: code = main()
    except Exception as error:
        code = 1
        print('Demo failed at ' + STAGE + ': ' + str(error), file=sys.stderr)
    result = dict(status='passed' if code == 0 else 'failed', stage=STAGE, exit_code=code,
        timestamp=__import__('datetime').datetime.now().astimezone().isoformat(),
        newman='skipped' if '--skip-newman' in sys.argv else 'requested')
    output = ROOT / 'docs/evidence/demo.json'; output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    sys.exit(code)
