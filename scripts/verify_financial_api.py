"""Read synthetic financial results through four authenticated loopback API sessions; never post payments."""
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
        req=urllib.request.Request(origin+path,data=body,headers=headers or {})
        try:
            with client.open(req,timeout=10) as response:
                data=response.read()
                return response.status,json.loads(data,parse_float=Decimal) if data else None
        except urllib.error.HTTPError as error:
            return error.code,None
    def expect(value,message):
        if not value:
            raise RuntimeError(message)
    def session(user):
        client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status,csrf=request(client,"/api/csrf")
        expect(status==200,"CSRF unavailable")
        status,_=request(client,"/api/login",urllib.parse.urlencode({"username":user,"password":os.environ.get("LOCAL_DEMO_PASSWORD","atlas-demo")}).encode(),{"Content-Type":"application/x-www-form-urlencoded",csrf["headerName"]:csrf["token"]})
        expect(status==204,"Synthetic login failed")
        return client
    hr,worker,finance,outsider=[session(u) for u in ["amelia","sophia","marcus","outsider"]]
    status,page=request(hr,"/api/v1/payroll/runs?entity=DEMO-FIN")
    expect(status==200 and page["items"],"Prepared payroll fixture required")
    finalized=[r for r in page["items"] if r["status"]=="FINALIZED" and r["journalId"]]
    expect(bool(finalized),"Finalized linked fixture required")
    checked=0
    for run in finalized:
        expect(run["ruleVersion"].startswith("DEMO-") and run["paymentStatus"]=="NOT_CONFIGURED","Unexpected live processing claim")
        status,journal=request(finance,"/api/v1/finance/journals/"+run["journalId"])
        expect(status==200 and journal["status"]=="POSTED" and journal["sourceId"]==run["id"],"Posted source unavailable")
        debit=sum((Decimal(str(l["debit"])) for l in journal["lines"]),Decimal(0))
        credit=sum((Decimal(str(l["credit"])) for l in journal["lines"]),Decimal(0))
        expect(debit==credit==Decimal(str(run["gross"])) and Decimal(str(run["gross"]))-Decimal(str(run["deduction"]))==Decimal(str(run["net"])),"Control totals differ")
        expect(request(outsider,"/api/v1/payroll/runs/"+run["id"])[0]==403,"Cross-tenant access allowed")
        expect(request(finance,"/api/v1/payroll/runs/"+run["id"])[0]==403,"Finance grant leaked payroll")
        expect(request(worker,"/api/v1/payroll/runs/"+run["id"])[0]==200,"Own finalized result unavailable")
        expect(request(worker,"/api/v1/finance/journals/"+journal["id"])[0]==403,"Employee ledger access allowed")
        checked+=1
    return {"recordedAt":datetime.now(timezone.utc).isoformat(),"result":"PASSED","scope":"Authenticated local reads after final package restart; no financial mutation or bank verification","rolesChecked":4,"linkedFinalizedResultsChecked":checked,"controlTotalsMatched":True,"crossTenantDenied":True,"financeOnlyPayrollAccessDenied":True,"employeeLedgerAccessDenied":True}

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin",default="http://127.0.0.1:3000")
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    if args.output.exists():
        raise SystemExit("Refusing to overwrite prior evidence")
    report=verify(args.origin)
    with args.output.open("x") as file:
        json.dump(report,file,indent=2)
        file.write("\n")
    print(json.dumps(report))
