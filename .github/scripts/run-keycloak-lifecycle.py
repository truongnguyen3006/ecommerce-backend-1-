#!/usr/bin/env python3
"""Own a fresh Keycloak 26.8.0 fixture and run explicit opt-in lifecycle tests.

Usage: python3 .github/scripts/run-keycloak-lifecycle.py /path/keycloak-26.8.0.tar.gz
The caller provides Java 24 on JAVA_HOME and mvn on PATH (or MAVEN_EXECUTABLE).
Never connects to a pre-existing realm; generated secrets are not printed.
"""
import hashlib
import html
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = Path(sys.argv[1]).resolve()
assert hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() == '9e41da899f838a58cd510fc98ed4f7cadc715aed5683e42aca20a0c9a2a3980a'
EVIDENCE = Path(os.environ.get('KEYCLOAK_FIXTURE_EVIDENCE', str(ROOT/'.github/runtime/keycloak-fixture')))
EVIDENCE.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='project1-owned-keycloak-') as directory:
    temp = Path(directory)
    with tarfile.open(ARCHIVE) as archive:
        archive.extractall(temp, filter='data')
    kc = temp/'keycloak-26.8.0'
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port = sock.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    realm = 'fixture-'+secrets.token_hex(6)
    client, secret = 'fixture-client', secrets.token_hex(32)
    text = (ROOT/'deploy/keycloak/realm-production.template.json').read_text()
    for key, value in {'KEYCLOAK_REALM':realm,'KEYCLOAK_CLIENT_ID':client,'KEYCLOAK_CLIENT_SECRET':secret,'PUBLIC_FRONTEND_URL':base}.items():
        text = text.replace('${'+key+'}',value)
    data = json.loads(text)
    # Loopback-only dev-file fixture; this does not alter the production template's TLS policy.
    data['sslRequired'] = 'none'
    (kc/'data/import').mkdir(parents=True)
    (kc/'data/import'/f'{realm}-realm.json').write_text(json.dumps(data))
    env = dict(os.environ,JAVA_OPTS_APPEND='-Xms128m -Xmx768m',TEST_KEYCLOAK_URL=base,
               TEST_KEYCLOAK_REALM=realm,TEST_KEYCLOAK_CLIENT_ID=client,TEST_KEYCLOAK_CLIENT_SECRET=secret)
    with (EVIDENCE/'runtime.log').open('w') as log:
        process = subprocess.Popen(['bash',str(kc/'bin/kc.sh'),'start-dev','--db=dev-file',
            '--http-host=127.0.0.1','--http-port='+str(port),'--hostname='+base,'--import-realm'],
            cwd=kc,env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(180):
                if process.poll() is not None:
                    raise RuntimeError('Owned Keycloak fixture exited; inspect private fixture runtime log')
                try:
                    with urllib.request.urlopen(base+'/realms/'+realm+'/.well-known/openid-configuration',timeout=2) as response:
                        assert json.loads(response.read())['issuer'] == base+'/realms/'+realm
                    break
                except Exception:
                    time.sleep(1)
            else:
                raise RuntimeError('Owned Keycloak readiness timeout')
            source = temp/'source';source.mkdir()
            files = subprocess.check_output(['git','ls-files','-co','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
            for name in set(files):
                if name:
                    dest = source/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,dest)
            args = [os.environ.get('MAVEN_EXECUTABLE','mvn'),'-B','-ntp']
            if env.get('HTTPS_PROXY'):
                proxy = urllib.parse.urlparse(env['HTTPS_PROXY'])
                settings = source/'fixture-maven-settings.xml'
                settings.write_text('<settings><proxies><proxy><id>fixture-network</id><active>true</active><protocol>http</protocol><host>'+html.escape(proxy.hostname)+'</host><port>'+str(proxy.port)+'</port></proxy></proxies></settings>')
                args += ['-s',str(settings)]
            args += ['-pl','user-service','-am','-Dtest=KeycloakLifecycleIT','-Dsurefire.failIfNoSpecifiedTests=false','test']
            result = subprocess.run(args,cwd=source,env=env)
            shutil.copytree(source/'user-service/target/surefire-reports',EVIDENCE/'surefire',dirs_exist_ok=True)
            if result.returncode:
                raise SystemExit(result.returncode)
            print('Owned Keycloak 26.8.0 lifecycle: five tests passed; no owner credentials/data used.')
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill();process.wait()
