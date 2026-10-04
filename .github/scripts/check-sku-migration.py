#!/usr/bin/env python3
"""Execute the new catalog SQL on an owned MySQL fixture before image builds.

This tests SQL compatibility/backfill constraints, not Flyway/Hibernate startup.
Existing owner's migration history is never repaired or changed.
"""
import os
import re
import secrets
import subprocess
import tempfile
import time
from pathlib import Path

root = Path(__file__).resolve().parents[2]
sha = os.environ.get('GITHUB_SHA','')
if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch('[a-f0-9]{40}',sha):
    raise SystemExit('Only an owned disposable CI runner may execute this fixture.')
name = 'project1-sku-fixture-'+sha[:12]+'-'+secrets.token_hex(3)
image = 'mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6'

def execute(args, data=None, required=True):
    result = subprocess.run(args,input=data,capture_output=True,text=True,timeout=180)
    if required and result.returncode:
        # Generated credentials are not in arguments; SQL contains only fixture data.
        raise RuntimeError('Disposable MySQL check failed: '+result.stderr[-1200:])
    return result

def sql(text, required=True):
    return execute(['docker','exec','-i',name,'sh','-c',
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'],text,required)

with tempfile.TemporaryDirectory(prefix='project1-sku-fixture-') as temp:
    env_file = Path(temp)/'fixture.env'
    env_file.write_text('MYSQL_ROOT_PASSWORD='+secrets.token_hex(32)+'\n');env_file.chmod(0o600)
    try:
        execute(['docker','run','--detach','--name',name,'--network','none','--env-file',str(env_file),image])
        for _ in range(90):
            final_server = execute(['docker','exec',name,'sh','-c','test "$(cat /proc/1/comm)" = mysqld'],required=False)
            if final_server.returncode == 0 and sql('SELECT 1;',required=False).returncode == 0:
                break
            time.sleep(2)
        else:
            raise RuntimeError('Owned MySQL fixture readiness timeout')
        folder = root/'product-service/src/main/resources/db/migration'
        sql('CREATE DATABASE fixture CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; USE fixture;\n'+
            (folder/'V1__current_schema.sql').read_text()+'\n'+(folder/'V3__transactional_outbox.sql').read_text())
        sql("USE fixture; INSERT INTO product(name,base_price) VALUES ('Fixture',1); INSERT INTO product_variant(sku_code,product_id) VALUES ('LIVE',1); INSERT INTO outbox_event(event_id,aggregate_key,topic,event_type,payload,created_at,next_attempt_at) VALUES ('fixture-live','LIVE','product-created-topic','fixture','{}',NOW(6),NOW(6)),('fixture-retired','RETIRED','product-created-topic','fixture','{}',NOW(6),NOW(6));")
        sql('USE fixture;\n'+(folder/'V4__permanent_sku_identity.sql').read_text()+'\n'+(folder/'V5__checked_catalog_revision.sql').read_text())
        assert sql('USE fixture; SELECT sku_code,retired+0 FROM sku_identity ORDER BY sku_code;').stdout.strip() == 'LIVE\t0\nRETIRED\t1'
        assert sql('USE fixture; INSERT INTO sku_identity(sku_code) VALUES (\'LIVE\');',required=False).returncode != 0
        assert sql('USE fixture; UPDATE product SET revision=-1;',required=False).returncode != 0
        print('MySQL 8.4.10: new SKU migration/backfill/default precision/unique identity/nonnegative revision passed.')
        # Exercise the actual production DLT statement, not an H2-only SQL approximation.
        migration = (root/'order-service/src/main/resources/db/migration/V5__durable_saga_recovery.sql').read_text()
        sql('USE fixture;\n'+migration[migration.index('CREATE TABLE workflow_dead_letter'):])
        source = (root/'order-service/src/main/java/com/myexampleproject/orderservice/service/WorkflowDeadLetters.java').read_text()
        statement = re.search(r'jdbc\.update\("([^"]+)"', source).group(1)
        assert statement.count('?') in (4, 7)
        def record(offset, order, topic='fixture.DLT', required=True):
            arguments = [topic, 0, offset, order]
            if statement.count('?') == 7:
                arguments += [topic, 0, offset]
            def literal(value):
                return 'NULL' if value is None else ("'"+value.replace("'", "''")+"'" if isinstance(value,str) else str(value))
            literals = iter(map(literal, arguments))
            return sql('USE fixture; '+re.sub(r'\?',lambda _:next(literals),statement)+';',required)
        record(1,'fixture-order')
        original = sql('USE fixture; SELECT * FROM workflow_dead_letter;').stdout
        record(1,'different-fixture-order')
        assert sql('USE fixture; SELECT * FROM workflow_dead_letter;').stdout == original
        record(2,None)
        assert sql('USE fixture; SELECT COUNT(*) FROM workflow_dead_letter;').stdout.strip() == '2'
        assert record(3,None,topic=None,required=False).returncode != 0
        assert sql('USE fixture; SELECT COUNT(*) FROM workflow_dead_letter;').stdout.strip() == '2'
        print('MySQL 8.4.10: actual DLT SQL stores stable evidence, preserves duplicates and propagates constraint failures.')
    finally:
        execute(['docker','rm','--force','--volumes',name],required=False)
