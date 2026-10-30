"""Start isolated real services, run Newman PKCE, then stop all processes."""
import argparse
import base64
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def ready(url, process, expected):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Service exited before readiness: ' + url)
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                if response.status == expected: return
        except urllib.error.HTTPError as error:
            if error.code == expected: return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(.25)
    raise RuntimeError('Readiness timeout: ' + url)

def stop(process):
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true')
    args = parser.parse_args()
    for port in (9000, 9001, 9002):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    env = os.environ.copy()
    for i in range(1, 6):
        env[f'OAUTH_USER{i}_PASSWORD'] = secrets.token_urlsafe(24)
    for name in ('OAUTH_CLIENT_SECRET', 'KEY_SERVICE_SIGNING_SECRET', 'KEY_SERVICE_ADMIN_SECRET'):
        env[name] = secrets.token_urlsafe(32)
    for name in ('OAUTH_STORAGE_KEY', 'KEY_SERVICE_MASTER_KEY'):
        env[name] = base64.b64encode(secrets.token_bytes(32)).decode()
    env.update(OAUTH_SECURE_COOKIE='false',
        OAUTH_REDIRECT_URI='http://127.0.0.1:8080/callback',
        KEY_SERVICE_URL='http://127.0.0.1:9002', RESOURCE_SERVER_JWKS_URI='http://127.0.0.1:9002/oauth2/jwks',
        PKCE_AS_URL='http://127.0.0.1:9000', PKCE_RS_URL='http://127.0.0.1:9001')
    info = subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True,check=True)
    home = re.search(r'^\s*java.home\s*=\s*(.+)$',info.stderr,re.MULTILINE)
    if not home:
        raise RuntimeError('Cannot locate Java')
    java = Path(home.group(1).strip())/'bin'/('java.exe' if os.name=='nt' else 'java')
    if not args.skip_build:
        subprocess.run([sys.executable,str(ROOT/'scripts/verify.py')],cwd=ROOT,env=env,check=True)
    (ROOT/'.work').mkdir(exist_ok=True)
    processes = []
    with tempfile.TemporaryDirectory(prefix='pkce-e2e-',dir=ROOT/'.work') as directory:
        env['KEY_SERVICE_DIRECTORY'] = str(Path(directory)/'keys')
        env['OAUTH_STORAGE_DIRECTORY'] = str(Path(directory)/'auth')
        try:
            for module,suffix,url,status in [
                ('key-service','-exec','http://127.0.0.1:9002/oauth2/jwks',200),
                ('authorization-server','','http://127.0.0.1:9000/oauth2/jwks',200),
                ('resource-server','','http://127.0.0.1:9001/hello',401)]:
                service_env = env.copy()
                if module != 'key-service':
                    for name in ('KEY_SERVICE_MASTER_KEY','KEY_SERVICE_ADMIN_SECRET','KEY_SERVICE_DIRECTORY'):
                        service_env.pop(name,None)
                if module != 'authorization-server':
                    for name in list(service_env):
                        if name.startswith('OAUTH_'): service_env.pop(name)
                if module == 'resource-server': service_env.pop('KEY_SERVICE_SIGNING_SECRET',None)
                jar=ROOT/module/'target'/f'{module}-1.0.0-SNAPSHOT{suffix}.jar'
                if not jar.exists(): raise RuntimeError(f'Missing JAR: {module}')
                process=subprocess.Popen([str(java),'-jar',str(jar)],cwd=ROOT,env=service_env,
                    stdout=subprocess.DEVNULL,stderr=subprocess.STDOUT)
                processes.append(process)
                ready(url,process,status)
            subprocess.run(['node',str(ROOT/'scripts/run-newman-pkce.cjs')],cwd=ROOT,env=env,check=True)
        finally:
            for process in reversed(processes): stop(process)

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('PKCE demo failed: '+str(error),file=sys.stderr)
        sys.exit(1)
