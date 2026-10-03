#!/usr/bin/env python3
"""Validate configuration without executing .env contents or printing secret values."""
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

def read_env(path):
    result = {}
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'): continue
        key, separator, value = line.partition('=')
        if not separator or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key) or key in result:
            raise ValueError(f'Invalid or duplicate variable at line {number}')
        if any(char in value for char in ['\n', '\r', '"', "'", '$', '`']):
            raise ValueError(f'{key}: use a literal unquoted value')
        result[key] = value
    return result

def validate(values):
    errors = []
    required = ['COMPOSE_PROJECT_NAME', 'IMAGE_NAMESPACE', 'IMAGE_TAG', 'PUBLIC_APP_HOST', 'PUBLIC_AUTH_HOST',
        'PUBLIC_FRONTEND_URL', 'NEXT_PUBLIC_WS_URL', 'KEYCLOAK_HOSTNAME', 'KEYCLOAK_ISSUER_URI',
        'KEYCLOAK_REALM', 'KEYCLOAK_CLIENT_ID', 'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME', 'EUREKA_USERNAME',
        'GRAFANA_ADMIN_USERNAME', 'KAFKA_CLUSTER_ID', 'VNPAY_API_URL', 'VNPAY_RETURN_URL', 'VNPAY_IPN_URL']
    credentials = ['MYSQL_ROOT_PASSWORD', 'KEYCLOAK_DB_ROOT_PASSWORD', 'KEYCLOAK_DB_PASSWORD',
        'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD', 'KEYCLOAK_CLIENT_SECRET', 'EUREKA_PASSWORD',
        'REDIS_PASSWORD', 'GRAFANA_ADMIN_PASSWORD']
    credentials += [f'{service}_DB_{suffix}' for service in ['PRODUCT','ORDER','PAYMENT','USER','CART']
        for suffix in ['PASSWORD','MIGRATION_PASSWORD']]
    for key in required + credentials:
        if not values.get(key) or 'REPLACE' in values[key] or 'example.invalid' in values[key]: errors.append(key + ': required real configuration')
    for key in credentials:
        if not re.fullmatch(r'[a-fA-F0-9]{64}', values.get(key, '')): errors.append(key + ': generate 64 hex characters')
    for key in ['PUBLIC_APP_HOST', 'PUBLIC_AUTH_HOST', 'KEYCLOAK_REALM', 'KEYCLOAK_CLIENT_ID', 'COMPOSE_PROJECT_NAME', 'EUREKA_USERNAME']:
        if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}', values.get(key, '')): errors.append(key + ': invalid identifier')
    if values.get('PUBLIC_APP_HOST') == values.get('PUBLIC_AUTH_HOST'): errors.append('Public application and auth hosts must differ')
    for key in ['PUBLIC_FRONTEND_URL','KEYCLOAK_HOSTNAME','KEYCLOAK_ISSUER_URI','VNPAY_API_URL','VNPAY_RETURN_URL','VNPAY_IPN_URL','NEXT_PUBLIC_WS_URL']:
        parsed = urlsplit(values.get(key, ''))
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.port not in [None,443]: errors.append(key + ': require a public HTTPS URL without credentials')
    app = 'https://' + values.get('PUBLIC_APP_HOST','')
    auth = 'https://' + values.get('PUBLIC_AUTH_HOST','')
    expected = {'PUBLIC_FRONTEND_URL':app,'KEYCLOAK_HOSTNAME':auth,
        'KEYCLOAK_ISSUER_URI':auth+'/realms/'+values.get('KEYCLOAK_REALM',''),
        'NEXT_PUBLIC_WS_URL':app+'/ws','VNPAY_RETURN_URL':app+'/api/payment/vnpay/return',
        'VNPAY_IPN_URL':app+'/api/payment/vnpay/ipn'}
    for key, value in expected.items():
        if values.get(key) != value: errors.append(key + ': inconsistent with public hosts/paths')
    if not re.fullmatch(r'[A-Za-z0-9_-]{22}', values.get('KAFKA_CLUSTER_ID','')): errors.append('KAFKA_CLUSTER_ID: require stable 22-character cluster ID')
    if not re.fullmatch(r'[a-z0-9./_-]+', values.get('IMAGE_NAMESPACE','')): errors.append('IMAGE_NAMESPACE: use lowercase registry namespace')
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', values.get('IMAGE_TAG','')) or values.get('IMAGE_TAG') == 'latest': errors.append('IMAGE_TAG: require immutable release tag')
    for keys in [('CLOUDINARY_CLOUD_NAME','CLOUDINARY_API_KEY','CLOUDINARY_API_SECRET'),('VNPAY_TMN_CODE','VNPAY_SECRET_KEY')]:
        present = [bool(values.get(key)) for key in keys]
        if any(present) and not all(present): errors.append('Incomplete external integration variables: ' + ', '.join(keys))
    try:
        if not 0 <= float(values.get('TRACING_SAMPLE_RATE','0.1')) <= 0.25: errors.append('TRACING_SAMPLE_RATE: use an intentional value from 0 through 0.25')
    except ValueError: errors.append('TRACING_SAMPLE_RATE: invalid probability')
    return errors

if __name__ == '__main__':
    try:
        values = read_env(sys.argv[1] if len(sys.argv) > 1 else '.env.production')
        errors = validate(values)
        if errors:
            print('Configuration validation failed:\n' + '\n'.join(errors), file=sys.stderr); sys.exit(2)
        print('Production configuration structure validated; external credentials still require live checks.')
    except (ValueError, OSError) as failure:
        print('Configuration validation failed: ' + str(failure), file=sys.stderr); sys.exit(2)
