"""Authenticated loopback talent recovery reads; no business mutation or external processing."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import http.cookiejar
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request


def verify(origin):
    if not re.fullmatch(r"http://127\.0\.0\.1:[0-9]{2,5}", origin):
        raise ValueError("Only local IPv4 loopback origins are supported")
    def request(client,path,body=None,headers=None):
        try:
            with client.open(urllib.request.Request(origin+path,data=body,headers=headers or {}),timeout=10) as r:
                raw=r.read()
                return r.status,json.loads(raw,parse_float=Decimal) if raw else None
        except urllib.error.HTTPError as e:
            return e.code,None
    def expect(ok,message):
        if not ok:raise RuntimeError(message)
    def session(user):
        c=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status,csrf=request(c,'/api/csrf');expect(status==200,'CSRF unavailable')
        status,_=request(c,'/api/login',urllib.parse.urlencode({'username':user,'password':os.environ.get('LOCAL_DEMO_PASSWORD','atlas-demo')}).encode(),{'Content-Type':'application/x-www-form-urlencoded',csrf['headerName']:csrf['token']})
        expect(status==204,'Synthetic login failed')
        return c
    hr,worker,manager,outsider=[session(u) for u in ['amelia','sophia','marcus','outsider']]
    checked=0
    for kind in ['requisitions','onboarding','reviews','courses','learning','compensation','succession','scenarios']:
        status,page=request(hr,'/api/v1/talent/'+kind)
        expect(status==200 and page['items'],'Persisted talent fixture unavailable')
        item=page['items'][0];record_id=item['meta']['id']
        status,detail=request(hr,f'/api/v1/talent/{kind}/{record_id}')
        expect(status==200 and detail==item,'Recovered detail differs from register')
        status,history=request(hr,f'/api/v1/talent/{kind}/{record_id}/history')
        expect(status==200 and bool(history),'Recovered audit history unavailable')
        expect(request(outsider,f'/api/v1/talent/{kind}/{record_id}')[0]==403,'Cross-tenant record access allowed')
        if kind=='onboarding' and item['employeeId']!='E003':expect(request(worker,f'/api/v1/talent/{kind}/{record_id}')[0]==403,'Same-tenant employee ownership bypass allowed')
        checked+=1
    expect(request(worker,'/api/v1/talent/compensation')[0]==403,'Employee confidential compensation access allowed')
    expect(request(manager,'/api/v1/talent/succession')[0]==403,'Manager confidential succession access allowed')
    for kind in ['reviews','learning']:
        status,page=request(worker,'/api/v1/talent/'+kind)
        expect(status==200 and page['items'] and all(x['employeeId']=='E003' for x in page['items']),'Owned employee scope differs')
    status,intakes=request(hr,'/api/v1/payroll/talent-inputs')
    expect(status==200 and bool(intakes),'Payroll receipt unavailable')
    for i in intakes:
        expect(i['status'] in ('RECEIVED','APPLIED'),'Unexpected calculated/paid claim')
        status,plan=request(hr,'/api/v1/talent/compensation/'+i['sourceId'])
        expect(status==200 and plan['meta']['status']=='APPROVED' and plan['payrollIntakeId']==i['id'] and plan['proposals']==i['awards'],'Payroll receipt does not reconcile')
    return {'recordedAt':datetime.now(timezone.utc).isoformat(),'result':'PASSED','scope':__doc__,'rolesChecked':4,'capabilityRegistersChecked':checked,'historiesAvailable':True,'ownershipAndTenantDenials':True,'sameTenantJoiningRecordDenied':True,'payrollIntakesReconciled':len(intakes)}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--origin',default='http://127.0.0.1:3000')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    if args.output.exists():raise SystemExit('Refusing to overwrite prior evidence')
    report=verify(args.origin)
    with args.output.open('x') as f:json.dump(report,f,indent=2);f.write('\n')
    print(json.dumps(report))
