#!/usr/bin/env python3
"""Copy an environment-placeholder realm template; never write real secrets to disk."""
import importlib.util
import json
import sys
from pathlib import Path
root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('production_env', root/'scripts/validate-production-env.py')
env = importlib.util.module_from_spec(spec); spec.loader.exec_module(env)
values = env.read_env(sys.argv[1] if len(sys.argv) > 1 else root/'.env.production')
errors = env.validate(values)
if errors: print('Validate production environment before preparing Keycloak.', file=sys.stderr); sys.exit(2)
template = root/'deploy/keycloak/realm-production.template.json'
json.loads(template.read_text())
output = root/'deploy/keycloak/runtime'/f"{values['KEYCLOAK_REALM']}-realm.json"
output.parent.mkdir(exist_ok=True)
if output.exists():
    if output.read_bytes() != template.read_bytes():
        print('Existing import file differs; review it manually. No overwrite performed.', file=sys.stderr); sys.exit(2)
else: output.write_bytes(template.read_bytes())
print('Realm placeholder template prepared; startup skips an existing realm.')
