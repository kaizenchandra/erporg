"""Create one synthetic user in the existing loopback-only Keycloak test realm.
No app privileges or production provisioning. Never prints tokens or credentials.
"""
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from uuid import uuid4

def create(output):
    if output.exists():
        raise ValueError('Refusing to overwrite a previous identity fixture')
    origin='http://127.0.0.1:8088'
    credentials={'client_id':'admin-cli','grant_type':'password','username':'local-admin','password':os.environ.get('KEYCLOAK_TEST_ADMIN_PASSWORD','local-synthetic-admin')}
    with urlopen(Request(origin+'/realms/master/protocol/openid-connect/token',data=urlencode(credentials).encode()),timeout=20) as r:
        token=json.load(r)['access_token']
    username='talent-joiner-'+uuid4().hex[:12]
    user={'username':username,'enabled':True,'emailVerified':True,'firstName':'Newhire','lastName':'Synthetic','email':username+'@example.test','credentials':[{'type':'password','value':'atlas-demo','temporary':False}]}
    with urlopen(Request(origin+'/admin/realms/atlas/users',data=json.dumps(user).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}),timeout=20) as r:
        if r.status!=201:raise RuntimeError('Identity fixture was not created')
        subject=r.headers['Location'].rsplit('/',1)[-1]
    output.write_text(json.dumps({'developmentOnly':True,'issuer':origin+'/realms/atlas','username':username,'subject':subject},indent=2)+'\n')
    print('Created one synthetic provider identity; its verified subject is stored in the specified test-fixture file.')
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);create(p.parse_args().output)
