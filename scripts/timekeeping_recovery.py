"""Recovered timekeeping reads and recorded-command replay; no employee values in evidence."""
import json
import re
import urllib.parse
from datetime import datetime
from restore_check import sql


def verify(target, client, request, expect):
    def instant(value):
        return datetime.fromisoformat(value.replace("Z", "+00:00")) if value else None

    def rows(session, path):
        result = []
        page = 0
        while True:
            status, data = request(session, path + ("&" if "?" in path else "?") + "size=50&page=" + str(page))
            expect(status == 200 and data["page"] == page and data["size"] == 50, "Recovered time page unavailable")
            result.extend(data["items"])
            expect(data["total"] <= 10000, "Recovered time fixture workload exceeded")
            if len(result) >= data["total"]:
                expect(len(result) == data["total"], "Recovered time page total differs")
                return result
            expect(bool(data["items"]), "Recovered time page did not advance")
            page += 1

    checked = {"sheets": 0, "allocations": 0, "attendance": 0, "audit": 0, "periodRevisions": 0, "replayedPeriodCommands": 0, "replayedBatches": 0}
    expect(int(sql(target, "select count(*) from atlas_acceptance.time_sheet where status='APPROVED'")) >= 2, "Approved recovered time fixtures required")
    for user in ["amelia", "sophia", "marcus", "outsider"]:
        session = client()
        _, token = request(session, "/api/csrf")
        credentials = urllib.parse.urlencode({"username": user, "password": "atlas-demo"}).encode()
        expect(request(session, "/api/login", credentials, {token["headerName"]: token["token"], "Content-Type": "application/x-www-form-urlencoded"})[0] == 204, "Recovered time sign-in failed")
        _, actor = request(session, "/api/v1/session")
        tenant, employee = actor["tenant"], actor["employeeId"]
        expect(re.fullmatch(r"[a-zA-Z0-9-]+", tenant) and re.fullmatch(r"[a-zA-Z0-9-]+", employee), "Unexpected synthetic scope token")
        weeks = json.loads(sql(target, "select coalesce(json_agg(distinct week),'[]') from atlas_acceptance.time_period"))
        for week in weeks:
            expect(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}",week) is not None,"Unexpected recovered reporting week")
            status, controls=request(session,"/api/v1/time/periods/"+week)
            expected_controls=json.loads(sql(target,"select coalesce(json_agg(snapshot order by version desc),'[]') from atlas_acceptance.time_period where tenant='"+tenant+"' and week='"+week+"'"))
            expect(status==200 and controls["history"]==expected_controls and controls["current"]==(expected_controls[0] if expected_controls else None),"Recovered period controls or tenant scope differ")
            checked["periodRevisions"]+=len(expected_controls)
            if user=="amelia" and expected_controls and expected_controls[0]["actor"]==user:
                original=expected_controls[0]
                payload={k:original[k] for k in ["week","deadline","dailyLimit","weeklyLimit","recallAllowed","closed","reason"]}
                payload.update(version=original["version"]-1,idempotencyKey=original["commandKey"])
                _,csrf=request(session,"/api/csrf")
                status,replayed=request(session,"/api/v1/time/periods",json.dumps(payload).encode(),{csrf["headerName"]:csrf["token"],"Content-Type":"application/json"})
                expect(status==200 and replayed==original,"Recovered policy command replay differs")
                checked["replayedPeriodCommands"]+=1
        if user=="marcus":
            batches=json.loads(sql(target,"select coalesce(json_agg(command_key),'[]') from atlas_acceptance.time_batch_command where tenant='"+tenant+"' and subject='marcus'"))
            for command_key in batches:
                expect(re.fullmatch(r"[a-zA-Z0-9-]{8,47}",command_key) is not None,"Unexpected recovered batch key")
                recorded=json.loads(sql(target,"select coalesce(json_agg(result->'sheet'),'[]') from atlas_acceptance.time_command where tenant='"+tenant+"' and subject='marcus' and command_key like '"+command_key+"-%'"))
                expect(1<=len(recorded)<=20 and all(recorded),"Recovered batch member receipts unavailable")
                action={"APPROVED":"APPROVE","RETURNED":"RETURN","REJECTED":"REJECT"}[recorded[0]["status"]]
                payload={"sheets":[{"id":sheet["id"],"version":sheet["version"]-1} for sheet in recorded],"action":action,"comment":recorded[0]["comment"],"idempotencyKey":command_key}
                _,csrf=request(session,"/api/csrf")
                status,replayed=request(session,"/api/v1/time/sheets/selected-actions",json.dumps(payload).encode(),{csrf["headerName"]:csrf["token"],"Content-Type":"application/json"})
                expect(status==200 and sorted(replayed,key=lambda sheet:sheet["id"])==sorted(recorded,key=lambda sheet:sheet["id"]),"Recovered atomic batch replay differs")
                checked["replayedBatches"]+=1

        own = rows(session, "/api/v1/time/sheets?review=false&status=ALL")
        saved = json.loads(sql(target, "select coalesce(json_agg(snapshot order by week desc,id),'[]') from atlas_acceptance.time_sheet where tenant='" + tenant + "' and employee_id='" + employee + "'"))
        expect(own == saved, "Recovered own timesheet snapshots differ")
        review = rows(session, "/api/v1/time/sheets?review=true&status=ALL")
        if actor["role"] == "HR":
            scope = "employee_id<>'" + employee + "'"
        elif actor["role"] == "MANAGER":
            visible = rows(session, "/api/v1/directory")
            ids = [row["id"] for row in visible if row["id"] != employee]
            expect(all(re.fullmatch(r"[a-zA-Z0-9-]+", value) for value in ids), "Unexpected directory scope token")
            scope = "employee_id in(" + ",".join("'" + value + "'" for value in (ids or ["__none__"])) + ")"
        else:
            scope = "false"
        expected = json.loads(sql(target, "select coalesce(json_agg(snapshot order by week desc,id),'[]') from atlas_acceptance.time_sheet where tenant='" + tenant + "' and " + scope))
        expect(review == expected, "Recovered current review scope differs")
        for sheet in own + review:
            expect(re.fullmatch(r"[a-f0-9-]{36}", sheet["id"]) is not None, "Unexpected synthetic sheet ID")
            status, context = request(session, "/api/v1/time/sheets/" + sheet["id"])
            expect(status == 200 and context["sheet"] == sheet, "Recovered private timesheet context differs")
            allocations = json.loads(sql(target, "select coalesce(json_agg(json_build_object('sheetId',sheet_id,'date',work_date,'minutes',minutes,'code',activity_code,'postedAt',posted_at) order by work_date),'[]') from atlas_acceptance.time_costing_entry where tenant='" + tenant + "' and sheet_id='" + sheet["id"] + "'"))
            for item in allocations + context["allocations"]:
                item["postedAt"] = instant(item["postedAt"])
            expect(context["allocations"] == allocations, "Recovered actual time allocations differ")
            expect(context["allocatedMinutes"] == sum(item["minutes"] for item in allocations), "Recovered time allocation total differs")
            expect(context["allocatedMinutes"] == (sheet["totalMinutes"] if sheet["status"] == "APPROVED" else 0), "Recovered approved hours do not reconcile")
            checked["sheets"] += 1
            checked["allocations"] += len(allocations)
        attendance = rows(session, "/api/v1/time/attendance/" + employee)
        expected_shifts = json.loads(sql(target, "select coalesce(json_agg(json_build_object('id',id,'employeeId',employee_id,'episodeStart',episode_start,'start',started_at,'end',ended_at,'zone',zone,'version',version,'reason',reason,'recordedAt',recorded_at) order by started_at desc,id),'[]') from atlas_acceptance.time_shift where tenant='" + tenant + "' and employee_id='" + employee + "'"))
        for item in attendance + expected_shifts:
            for key in ["start", "end", "recordedAt"]:
                item[key] = instant(item[key])
        expect(attendance == expected_shifts, "Recovered complete attendance differs")
        history = rows(session, "/api/v1/time/history/" + employee)
        expected_history = json.loads(sql(target, "select coalesce(json_agg(json_build_object('id',id::text,'entityId',entity_id,'action',action,'actor',actor,'reason',reason,'before',before_snapshot::text,'after',after_snapshot::text,'recordedAt',recorded_at,'correlationId',correlation_id) order by id desc),'[]') from atlas_acceptance.time_audit where tenant='" + tenant + "' and employee_id='" + employee + "'"))
        for item in history + expected_history:
            item["recordedAt"] = instant(item["recordedAt"])
            item["before"] = json.loads(item["before"])
            item["after"] = json.loads(item["after"])
        expect(history == expected_history, "Recovered complete time audit differs")
        forbidden = "E900" if actor["role"] == "HR" else ("E003" if user == "outsider" else "E001")
        expect(request(session, "/api/v1/time/attendance/" + forbidden)[0] == 403 and request(session, "/api/v1/time/history/" + forbidden)[0] == 403, "Recovered time record/tenant denial failed")
        checked["attendance"] += len(attendance)
        checked["audit"] += len(history)
        _, token = request(session, "/api/csrf")
        expect(request(session, "/api/logout", b"", {token["headerName"]: token["token"]})[0] == 204, "Recovered time logout failed")
    return {"status": "PASSED", "roles": ["HR", "self", "current manager", "other tenant"], "checkedAcrossRoleViews": checked, "scope": "complete persisted attendance, seven-day sheets, current review scope, exact immutable costing reconciliation weekly controls/revision history, persisted policy/bulk replay and full audit provenance compared with owned restored SQL; no values in report"}
