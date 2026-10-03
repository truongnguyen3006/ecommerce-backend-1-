#!/usr/bin/env python3
"""Business/fault/restore probe restricted to this commit's disposable CI projects.

All credentials are generated fixture data. No provider URL is contacted. SQL
dumps, tokens and volume archives remain in RUNNER_TEMP and are never artifacts.
Assertions fail closed; the report lists only checks which actually completed.
"""
import hashlib
import hmac
import importlib.util
import json
import os
import re
import secrets
import subprocess
import time
import urllib.parse
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / '.env.production.ci'
OVERRIDE = ROOT / '.github/runtime/smoke-override.json'
SHA = os.environ.get('GITHUB_SHA', '')
if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch('[a-f0-9]{40}', SHA):
    raise SystemExit('This probe is restricted to disposable GitHub Actions runners.')
spec = importlib.util.spec_from_file_location('production_env', ROOT/'scripts/validate-production-env.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
values = module.read_env(ENV)
PROJECT = 'project1-ci-' + SHA[:12]
assert values['COMPOSE_PROJECT_NAME'] == PROJECT and values['IMAGE_TAG'] == SHA
assert values['VNPAY_TMN_CODE'] == 'FIXTURE1'
assert values['VNPAY_API_URL'] == 'https://provider.example.test/payment'
PRIVATE = Path(os.environ['RUNNER_TEMP']) / ('project1-fixture-' + SHA[:12])
PRIVATE.mkdir(mode=0o700, exist_ok=False)
checks = []
active_env = ENV


def run(args, data=None, env=None, timeout=180):
    result = subprocess.run(args, input=data, text=True, capture_output=True,
                            cwd=ROOT, env=env, timeout=timeout)
    if result.returncode:
        # Commands may receive confidential fixture input; never print args/input/output.
        raise RuntimeError('Disposable command failed: ' + Path(args[0]).name)
    return result.stdout


def compose(*args, data=None, timeout=180):
    return run(['docker', 'compose', '--project-directory', str(ROOT), '--env-file',
                str(active_env), '-f', str(ROOT/'docker-compose.prod.yml'), '-f', str(OVERRIDE),
                *args], data=data, timeout=timeout)


def request(method, path, body=None, token=None, key=None, expected=(200,), base=None, form=False):
    url = (base or 'http://api-gateway:8080') + path
    assert urllib.parse.urlsplit(url).hostname in ('api-gateway', 'keycloak')
    config = ['silent', 'show-error', 'max-time = 20', 'request = '+json.dumps(method),
              'url = '+json.dumps(url), 'write-out = "\\n%{http_code}"']
    if token:
        config.append('header = '+json.dumps('Authorization: Bearer '+token))
    if key:
        config.append('header = '+json.dumps('Idempotency-Key: '+key))
    if base:
        config.extend(['header = "X-Forwarded-Proto: https"',
                       'header = '+json.dumps('X-Forwarded-Host: '+values['PUBLIC_AUTH_HOST'])])
    if body is not None:
        content = urllib.parse.urlencode(body) if form else json.dumps(body)
        config.extend(['header = '+json.dumps('Content-Type: '+('application/x-www-form-urlencoded' if form else 'application/json')),
                       'data = '+json.dumps(content)])
    output = compose('exec', '-T', 'api-gateway', 'curl', '--config', '-', data='\n'.join(config)+'\n')
    content, code = output.rsplit('\n', 1)
    code = int(code)
    if expected is not None and code not in expected:
        raise AssertionError('Fixture HTTP status '+str(code)+' for '+method+' '+path.split('?')[0])
    try:
        decoded = json.loads(content) if content else None
    except json.JSONDecodeError:
        decoded = None
    return code, decoded


def await_value(description, call, predicate, seconds=180):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        try:
            value = call()
            if predicate(value):
                return value
        except (AssertionError, RuntimeError):
            pass
        time.sleep(3)
    raise AssertionError('Timed out: '+description)


def passed(name):
    checks.append(name)
    print('PASS: '+name, flush=True)
    (ROOT/'.github/runtime/business-results.json').write_text(json.dumps({'commit':SHA, 'completed_checks':checks}, indent=2))


def sql(database, statement):
    assert database in ('product-service','order-service','payment-service','user-service','cart-service')
    return compose('exec', '-T', 'mysql', 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names '+database,
                   data=statement+';\n').strip()


def login(account):
    return request('POST','/auth/login',{'username':account['username'],'password':account['password']})[1]['access_token']


def stock(sku, quantity):
    return await_value('stock '+sku, lambda: request('GET','/api/inventory/'+sku)[1],
                       lambda data: data['quantity'] == quantity)


def status(order, target):
    return await_value('order '+target, lambda: request('GET','/api/order/'+order,token=user_token)[1],
                       lambda data: data['status'] == target)


def place(sku, quantity, method):
    key = str(uuid.uuid4())
    body = {'items':[{'skuCode':sku,'quantity':quantity}], 'paymentMethod':method,
            'shippingRecipientName':'Disposable fixture','shippingAddressLine':'Fixture address'}
    order = request('POST','/api/order',body,user_token,key,(202,))[1]['orderNumber']
    return order, key, body


def callback(transaction):
    params = {'vnp_TmnCode':values['VNPAY_TMN_CODE'], 'vnp_TxnRef':transaction['txnRef'],
              'vnp_Amount':str(int(float(transaction['amount'])*100)), 'vnp_ResponseCode':'00',
              'vnp_TransactionStatus':'00', 'vnp_TransactionNo':'fixture-'+secrets.token_hex(6)}
    query = urllib.parse.urlencode(sorted(params.items()))
    params['vnp_SecureHash'] = hmac.new(values['VNPAY_SECRET_KEY'].encode(), query.encode(), hashlib.sha512).hexdigest()
    return urllib.parse.urlencode(params)


def ipn(query, code):
    assert request('GET','/api/payment/vnpay/ipn?'+query)[1]['RspCode'] == code


def healthy(*services):
    compose('up','--no-build','--pull','never','--detach','--wait','--wait-timeout','600',*services,timeout=660)


def fingerprint():
    statements = {
        'user-service': ['SELECT keycloak_id,email,status+0 FROM t_user ORDER BY id',
                         'SELECT user_keycloak_id,id,is_default+0,default_owner_key FROM t_user_address ORDER BY id',
                         'SELECT intent_id,idempotency_key,state,keycloak_id FROM user_provisioning_intent ORDER BY intent_id'],
        'product-service':['SELECT id,name,revision,base_price FROM product ORDER BY id',
                           'SELECT sku_code,product_id,price FROM product_variant ORDER BY sku_code',
                           'SELECT sku_code,retired+0 FROM sku_identity ORDER BY sku_code'],
        'order-service':['SELECT order_number,user_id,status,total_price,payment_method,payment_attempt_id,payment_reconciliation_required+0 FROM t_orders ORDER BY order_number',
                         'SELECT order_id,sku_code,quantity,price,inventory_outcome+0 FROM t_orders_line_items ORDER BY id'],
        'payment-service':['SELECT order_number,txn_ref,status,amount,provider_success_received+0 FROM payment_transaction ORDER BY id'],
        'cart-service':[]}
    result = {}
    for database, queries in statements.items():
        queries.append('SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank')
        if database in ('product-service','order-service','payment-service'):
            queries.append('SELECT event_id,aggregate_key,topic,SHA2(payload,256) FROM outbox_event ORDER BY id')
        result[database] = [sql(database, query) for query in queries]
    return result


def volume_name(project, logical):
    name = project+'_'+logical
    labels = json.loads(run(['docker','volume','inspect',name]))[0]['Labels']
    assert labels['com.docker.compose.project'] == project and labels['com.docker.compose.volume'] == logical
    return name


def archive_volume(project, logical, restore=False):
    name = volume_name(project, logical)
    redis_image = 'redis:7.4.10-alpine@sha256:e7723ff73d963f5cc6d9c4643ea3d989527a402a319239054e9472a7fb9219a2'
    archive = PRIVATE/(logical+'.tar.gz')
    if restore:
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == archive.with_suffix('.sha256').read_text()
        command = 'test -z "$(ls -A /data)" && tar -xzf /backup/'+logical+'.tar.gz -C /data'
    else:
        assert not archive.exists()
        command = 'tar -czf /backup/'+logical+'.tar.gz -C /data .'
    run(['docker','run','--rm','--network','none','--user','0','--entrypoint','sh',
         '-v',name+':/data'+('' if restore else ':ro'),'-v',str(PRIVATE)+':/backup',redis_image,'-c',command])
    if not restore:
        archive.with_suffix('.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest())


try:
    accounts = []
    for _ in range(2):
        name = 'fixture-'+secrets.token_hex(8)
        account = {'username':name,'email':name+'@example.test','password':secrets.token_hex(16)+'-Aa1!', 'fullName':'Disposable fixture'}
        profile = await_value('registration route', lambda: request('POST','/auth/register',account,key=name,expected=(201,))[1], lambda data: bool(data.get('keycloakId')))
        assert request('POST','/auth/register',account,key=name,expected=(201,))[1]['keycloakId'] == profile['keycloakId']
        account['keycloakId'] = profile['keycloakId']
        accounts.append(account)
    passed('registration retry retains exact fixture SQL/Keycloak identity')
    user, admin = accounts
    user_token = login(user)
    kc_base = 'http://keycloak:8080'
    realm_path = '/admin/realms/'+values['KEYCLOAK_REALM']
    service_token = request('POST','/realms/'+values['KEYCLOAK_REALM']+'/protocol/openid-connect/token',
                            {'grant_type':'client_credentials','client_id':values['KEYCLOAK_CLIENT_ID'],'client_secret':values['KEYCLOAK_CLIENT_SECRET']},
                            base=kc_base, form=True)[1]['access_token']
    identity = request('GET',realm_path+'/users?'+urllib.parse.urlencode({'username':admin['username'],'exact':'true','briefRepresentation':'false'}),token=service_token,base=kc_base)[1]
    assert len(identity) == 1 and identity[0]['id'] == admin['keycloakId'] and identity[0]['email'] == admin['email']
    role = request('GET',realm_path+'/roles/ADMIN',token=service_token,base=kc_base)[1]
    request('POST',realm_path+'/users/'+admin['keycloakId']+'/role-mappings/realm',[role],token=service_token,base=kc_base,expected=(204,))
    admin_token = login(admin)
    request('GET','/api/order/admin',token=user_token,expected=(403,))
    request('GET','/api/order/admin',token=admin_token)
    request('GET','/api/inventory/operations/fixture-unclaimed',token=user_token,expected=(403,))
    request('GET','/api/order/internal/fixture/payment-context',token=admin_token,expected=(403,))
    request('GET','/api/cart/view/'+admin['keycloakId'],token=user_token,expected=(403,))
    passed('real USER/ADMIN boundaries and cart ownership; internal gateway route denied')
    address = {'recipientName':'Fixture','recipientPhone':'0000000000','addressLine':'Isolated fixture','isDefault':True}
    for _ in range(2):
        request('POST','/api/user/addresses',address,user_token,expected=(201,))
    addresses = request('GET','/api/user/addresses',token=user_token)[1]
    assert sum(bool(a['isDefault']) for a in addresses) == 1
    passed('fresh MySQL address invariant and atomic default replacement')
    sku_a, sku_b = ['FIXTURE-'+secrets.token_hex(6) for _ in range(2)]
    product_body = {'name':'Disposable business fixture','category':'  Fixture  ','basePrice':100000,
                    'variants':[{'skuCode':sku_a,'price':100000,'initialQuantity':12,'isActive':True},
                                {'skuCode':sku_b,'price':100000,'initialQuantity':8,'isActive':True}]}
    product = request('POST','/api/product',product_body,admin_token,expected=(201,))[1]
    stock(sku_a,12);stock(sku_b,8)
    update = dict(product_body, revision=product['revision'])
    request('PUT','/api/product/'+str(product['id']),update,admin_token)
    conflict = request('PUT','/api/product/'+str(product['id']),update,admin_token,expected=(409,))[1]
    assert conflict['code'] == 'PRODUCT_REVISION_CONFLICT'
    passed('fresh MySQL migrations/Hibernate startup, SKU INIT and stale revision guard')
    request('POST','/api/cart/items',{'skuCode':sku_a,'quantity':1},user_token)
    snapshot = request('GET','/api/cart/me',token=user_token)[1]['items'][0]
    cod, cod_key, cod_body = place(sku_a,1,'COD')
    status(cod,'COMPLETED');stock(sku_a,11)
    for quantity in (2,1):
        request('PUT','/api/cart/items/'+sku_a,{'skuCode':sku_a,'quantity':quantity},user_token)
    request('POST','/api/cart/items',{'skuCode':sku_b,'quantity':1},user_token)
    cleanup = {'orderNumber':cod,'items':[{'skuCode':sku_a,'quantity':1,'revision':snapshot['revision']}]}
    assert request('POST','/api/cart/purchased',cleanup,user_token)[1]['removed'] == 0
    assert len(request('GET','/api/cart/me',token=user_token)[1]['items']) == 2
    passed('COD completes once; changed cart revision and newly added line survive cleanup')
    online, _, _ = place(sku_a,2,'VNPAY');status(online,'VALIDATED');stock(sku_a,9)
    payment = request('POST','/api/payment/vnpay/create',{'orderNumber':online},user_token)[1]
    query = callback(payment)
    request('POST','/api/order/'+online+'/cancel',{},user_token,expected=(409,))
    request('GET','/api/payment/vnpay/return?'+query,expected=(302,))
    assert request('GET','/api/payment/order/'+online,token=user_token)[1]['status'] == 'PENDING'
    ipn(query,'00');ipn(query,'02')
    status(online,'COMPLETED')
    await_value('authoritative payment decision',lambda:request('GET','/api/payment/order/'+online,token=user_token)[1],lambda p:p['status']=='SUCCESS')
    request('POST','/api/order/'+online+'/cancel',{},user_token,expected=(409,));stock(sku_a,9)
    passed('mock Return is read-only; signed IPN/duplicate acknowledgement and paid cancellation fence')
    op = str(uuid.uuid4());adjustment = {'skuCode':sku_a,'adjustmentQuantity':3,'reason':'Disposable fixture'}
    request('POST','/api/inventory/adjust',adjustment,admin_token,op,(202,200))
    await_value('admin APPLIED',lambda:request('GET','/api/inventory/operations/'+op,token=admin_token)[1],lambda result:result['status']=='APPLIED')
    request('POST','/api/inventory/adjust',adjustment,admin_token,op,(200,202));stock(sku_a,12)
    rejected_op = str(uuid.uuid4())
    request('POST','/api/inventory/adjust',dict(adjustment,adjustmentQuantity=-100),admin_token,rejected_op,(202,))
    await_value('admin REJECTED',lambda:request('GET','/api/inventory/operations/'+rejected_op,token=admin_token)[1],lambda result:result['status']=='REJECTED')
    stock(sku_a,12)
    passed('admin retry adjusts once; excessive deduction rejected without negative stock')
    compose('stop','kafka')
    delayed, delayed_key, delayed_body = place(sku_b,1,'COD')
    assert sql('order-service',"SELECT COUNT(*) FROM outbox_event WHERE publication_state='PENDING'") != '0'
    compose('restart','order-service')
    healthy('kafka','schema-registry','order-service','inventory-service','payment-service')
    user_token = login(user);admin_token = login(admin)
    status(delayed,'COMPLETED');stock(sku_b,7)
    assert request('POST','/api/order',delayed_body,user_token,delayed_key,(202,))[1]['orderNumber'] == delayed
    passed('Kafka outage delays outbox; active order restart and broker recovery complete without second deduction')
    second_online, _, _ = place(sku_b,1,'VNPAY');status(second_online,'VALIDATED');stock(sku_b,6)
    second_payment = request('POST','/api/payment/vnpay/create',{'orderNumber':second_online},user_token)[1]
    second_query = callback(second_payment)
    compose('restart','payment-service');healthy('payment-service')
    user_token = login(user);admin_token = login(admin)
    ipn(second_query,'00');ipn(second_query,'02');status(second_online,'COMPLETED')
    await_value('restarted payment success',lambda:request('GET','/api/payment/order/'+second_online,token=user_token)[1],lambda p:p['status']=='SUCCESS')
    compose('kill','--signal','SIGKILL','inventory-service');healthy('inventory-service')
    user_token = login(user);admin_token = login(admin)
    stock(sku_a,12);stock(sku_b,6)
    request('POST','/api/inventory/adjust',adjustment,admin_token,op,(200,202));stock(sku_a,12)
    compose('restart','redis');healthy('redis','cart-service')
    user_token = login(user);admin_token = login(admin)
    preserved_cart = request('GET','/api/cart/me',token=user_token)[1]
    assert len(preserved_cart['items']) == 2
    passed('active payment restart/callback retry, inventory SIGKILL/RocksDB recovery and Redis AOF restart')
    # Replay only this fresh fixture's original stable INIT and CHECK outbox identities.
    sql('product-service',"UPDATE outbox_event SET publication_state='PENDING',next_attempt_at=UTC_TIMESTAMP(6) WHERE topic='product-created-topic' AND aggregate_key IN ('"+sku_a+"','"+sku_b+"')")
    sql('order-service',"UPDATE outbox_event SET publication_state='PENDING',next_attempt_at=UTC_TIMESTAMP(6) WHERE topic='inventory-check-request-topic' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.orderNumber')) IN ('"+cod+"','"+delayed+"','"+online+"','"+second_online+"')")
    for database in ('product-service','order-service','payment-service'):
        await_value('outbox drain '+database,lambda db=database:sql(db,"SELECT COUNT(*) FROM outbox_event WHERE publication_state<>'PUBLISHED'"),lambda count:count=='0')
    time.sleep(5)
    stock(sku_a,12);stock(sku_b,6)
    passed('duplicate stable INIT/CHECK delivery conserves stock; all business outbox rows publish')
    # A consistent boundary: stop writers and consumers, SQL dumps, then clean stopped volume snapshots.
    compose('stop','api-gateway','product-service','inventory-service','cart-service','order-service','payment-service','user-service','notification-service','discovery-server','keycloak')
    baseline = fingerprint()
    for service, filename in (('mysql','business.sql'),('keycloak-db','keycloak.sql')):
        run([str(ROOT/'scripts/backup-mysql.sh'),service,str(PRIVATE/filename)],env=dict(os.environ,PRODUCTION_ENV_FILE=str(active_env)))
    compose('exec','-T','redis','sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli SAVE')
    compose('stop',timeout=240)
    for logical in ('redis-data','kafka-data','inventory-streams'):
        archive_volume(PROJECT,logical)
    passed('coordinated business/Keycloak SQL dumps plus stopped Redis/Kafka/Streams snapshots and checksums')
    restore_project = PROJECT+'-restore'
    restore_values = dict(values,COMPOSE_PROJECT_NAME=restore_project)
    active_env = PRIVATE/'restore.env'
    active_env.write_text(''.join(k+'='+v+'\n' for k,v in restore_values.items()));active_env.chmod(0o600)
    compose('create','--no-build','--pull','never',timeout=240)
    # Both projects own distinct labelled volumes; source remains stopped and unchanged.
    for logical in ('business-db','keycloak-db','redis-data','kafka-data','inventory-streams'):
        assert volume_name(PROJECT,logical) != volume_name(restore_project,logical)
    for logical in ('redis-data','kafka-data','inventory-streams'):
        archive_volume(restore_project,logical,restore=True)
    healthy('mysql','keycloak-db')
    for service, filename in (('mysql','business.sql'),('keycloak-db','keycloak.sql')):
        run([str(ROOT/'scripts/restore-mysql.sh'),service,str(PRIVATE/filename)],env=dict(os.environ,PRODUCTION_ENV_FILE=str(active_env),RESTORE_ISOLATED_DATABASE_ACK='YES'))
    healthy()
    user_token = login(user);admin_token = login(admin)
    assert fingerprint() == baseline
    assert request('GET','/api/cart/me',token=user_token)[1] == preserved_cart
    stock(sku_a,12);stock(sku_b,6)
    for order in (cod,online,delayed,second_online):
        assert status(order,'COMPLETED')['userId'] == user['keycloakId']
    assert request('GET','/api/inventory/operations/'+op,token=admin_token)[1]['status'] == 'APPLIED'
    assert request('POST','/api/order',cod_body,user_token,cod_key,(202,))[1]['orderNumber'] == cod
    request('POST','/api/inventory/adjust',adjustment,admin_token,op,(200,202))
    ipn(query,'02');ipn(second_query,'02');stock(sku_a,12);stock(sku_b,6)
    passed('isolated restore conserves owners/SKUs/totals/payment refs/outbox IDs/cart revisions/operation IDs; retry remains idempotent')
    print('Disposable business, fault and coordinated restore rehearsal completed.',flush=True)
finally:
    if active_env != ENV:
        compose('down','--timeout','30',timeout=180)
