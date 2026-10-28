"""Exécute le cycle Maven et conserve des preuves assainies avec le code de sortie réel."""
import argparse, datetime, json, os, pathlib, re, shutil, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parents[1]

def sanitize(text):
    """Masque secrets, JWT et clés privées avant toute écriture des logs."""
    for name, value in os.environ.items():
        if value and (name.startswith(('OAUTH_', 'KEY_SERVICE_')) or any(s in name.upper() for s in ('TOKEN', 'PASSWORD', 'SECRET'))):
            text = text.replace(value, '[REDACTED]')
    text = re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*', '[REDACTED_JWT]', text)
    text = re.sub(r'-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----', '[REDACTED_PRIVATE_KEY]', text, flags=re.S)
    return text

def main():
    """Lance clean verify et copie uniquement les rapports créés durant cette exécution."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=pathlib.Path, default=ROOT / '.work/m2')
    parser.add_argument('--offline', action='store_true', help='Use only the existing local Maven cache')
    args = parser.parse_args()
    repo = args.repo.resolve(); repo.mkdir(parents=True, exist_ok=True)
    evidence = ROOT / 'docs/evidence' / ('verify-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(parents=True, exist_ok=False)
    maven = shutil.which('mvn') or shutil.which('mvn.cmd')
    command = [maven or 'mvn', '-B', '-ntp', '-o' if args.offline else '-U', '-Dmaven.repo.local=' + str(repo), 'clean', 'verify']
    started = datetime.datetime.now().astimezone().isoformat()
    start_epoch = time.time()
    try:
        process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, errors='replace')
        code = process.returncode
        output = sanitize(process.stdout + '\n' + process.stderr)
    except OSError as error:
        code = 127; output = sanitize(str(error))
    (evidence / 'maven.log').write_text(output, encoding='utf-8')
    result = dict(command=command, cwd=str(ROOT), started=started,
                  finished=datetime.datetime.now().astimezone().isoformat(), exit_code=code,
                  status='passed' if code == 0 else 'failed', repository=str(repo), stale_reports_ignored=0)
    # Les rapports anciens du dossier target ne doivent pas être présentés comme récents.
    for module in ('key-service', 'authorization-server', 'resource-server', 'coverage'):
        target = ROOT / module / 'target'
        for folder in ('surefire-reports', 'failsafe-reports', 'cucumber', 'cucumber-reports', 'site/jacoco', 'site/jacoco-aggregate'):
            source = target / folder
            if not source.exists(): continue
            destination = evidence / module / folder
            for file in source.rglob('*'):
                if not file.is_file(): continue
                if file.stat().st_mtime < start_epoch:
                    result['stale_reports_ignored'] += 1
                    continue
                output_file = destination / file.relative_to(source)
                output_file.parent.mkdir(parents=True, exist_ok=True)
                if file.suffix in ('.xml', '.json', '.html', '.txt', '.csv', '.js', '.css'):
                    output_file.write_text(sanitize(file.read_text(encoding='utf-8', errors='replace')), encoding='utf-8')
                else: shutil.copy2(file, output_file)
    (evidence / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('Maven exit ' + str(code) + '; sanitized evidence: ' + str(evidence))
    return code

if __name__ == '__main__': sys.exit(main())
