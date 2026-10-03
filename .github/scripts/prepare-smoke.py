#!/usr/bin/env python3
"""Create disposable GitHub CI configuration; never use owner credentials/data."""
import base64
import importlib.util
import json
import os
import re
import secrets
import uuid
from pathlib import Path

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('This fixture generator is restricted to disposable GitHub Actions runners.')
sha = os.environ.get('GITHUB_SHA', '')
if not re.fullmatch('[a-f0-9]{40}', sha):
    raise SystemExit('A verified CI commit SHA is required.')
root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('production_env', root/'scripts/validate-production-env.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
values = module.read_env(root/'.env.production.example')
for key, value in values.items():
    if value == 'REPLACE_WITH_64_HEX_CHARS':
        values[key] = secrets.token_hex(32)
    elif value == 'REPLACE_WITH_OPERATOR_USERNAME':
        values[key] = 'ci-operator'
    else:
        values[key] = value.replace('shop.example.invalid', 'ci-app.example.com').replace('auth.example.invalid', 'ci-auth.example.com')
values.update(COMPOSE_PROJECT_NAME='project1-ci-'+sha[:12], IMAGE_NAMESPACE='project1-validation',
              IMAGE_TAG=sha, FRONTEND_SOURCE='./frontend-ci', TRACING_SAMPLE_RATE='0',
              KAFKA_CLUSTER_ID=base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip('='),
              VNPAY_TMN_CODE='FIXTURE1', VNPAY_SECRET_KEY=secrets.token_hex(32),
              VNPAY_API_URL='https://provider.example.test/payment')
if module.validate(values):
    raise SystemExit('Generated isolated fixture failed configuration validation.')
target = root/'.env.production.ci'
with target.open('x') as output:
    output.write(''.join(key+'='+value+'\n' for key, value in values.items()))
target.chmod(0o600)

# Resource limits apply only to this empty CI stack; they are not capacity evidence.
apps = ['api-gateway', 'discovery-server', 'product-service', 'inventory-service', 'cart-service',
        'order-service', 'payment-service', 'user-service', 'notification-service']
services = {name: {'mem_limit': '768m', 'environment': {
    'JAVA_TOOL_OPTIONS': '-Xms64m -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError'+
        (' -Duser.timezone=Asia/Ho_Chi_Minh' if name == 'payment-service' else '')}} for name in apps}
for name, limit in {'mysql':'768m', 'keycloak-db':'768m', 'keycloak':'1g', 'redis':'256m',
                    'kafka':'1536m', 'schema-registry':'1g', 'frontend':'1g', 'prometheus':'256m',
                    'grafana':'256m', 'zipkin':'512m', 'reverse-proxy':'128m'}.items():
    services[name] = {'mem_limit': limit}
services['kafka']['environment'] = {'KAFKA_HEAP_OPTS':'-Xms256m -Xmx768m'}
runtime = root/'.github/runtime'
runtime.mkdir(exist_ok=True)
(runtime/'smoke-override.json').write_text(json.dumps({'services':services}))
print('Disposable CI configuration prepared; no real provider credentials or public TLS were used.')
