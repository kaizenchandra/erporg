create unique index benefit_distinct_dependants on benefit_dependant(tenant,selection_id,lower(name));
create function benefits_dependant_pending() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from benefit_selection s where s.tenant=NEW.tenant and s.id=NEW.selection_id and s.status='PENDING') then raise exception 'Dependants require a pending selection'; end if; return NEW; end $$;
create trigger benefit_dependant_pending before insert on benefit_dependant for each row execute function benefits_dependant_pending();
create function benefits_selection_integrity() returns trigger language plpgsql as $$
declare s benefit_selection%rowtype; submitted integer; decided integer; deductions integer;
begin
 select * into s from benefit_selection where tenant=NEW.tenant and id=NEW.id;
 if not exists(select 1 from benefit_plan p where p.tenant=s.tenant and p.code=s.plan_code and p.name=s.plan_name and p.currency=s.currency and p.monthly_contribution=s.monthly_contribution and p.rule_version=s.rule_version and p.effective_from<=s.effective_date) then raise exception 'Selection snapshot must match plan'; end if;
 select count(*) into submitted from benefit_selection_audit a where a.tenant=s.tenant and a.selection_id=s.id and a.action='SUBMITTED' and a.actor=s.requested_by and a.reason=s.reason;
 select count(*) into decided from benefit_selection_audit a where a.tenant=s.tenant and a.selection_id=s.id and a.action=s.status and a.actor=s.decided_by and a.reason=s.decision_reason;
 select count(*) into deductions from benefit_deduction_handoff h where h.tenant=s.tenant and h.selection_id=s.id;
 if submitted<>1 or (s.status='PENDING' and decided<>0) or (s.status<>'PENDING' and decided<>1) or (s.status='APPROVED' and deductions<>1) or (s.status<>'APPROVED' and deductions<>0) then raise exception 'Selection audit and deduction state must reconcile'; end if;
 return null;
end $$;
create constraint trigger benefit_selection_integrity after insert or update on benefit_selection deferrable initially deferred for each row execute function benefits_selection_integrity();
create function benefits_audit_state() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from benefit_selection s where s.tenant=NEW.tenant and s.id=NEW.selection_id and ((NEW.action='SUBMITTED' and s.status='PENDING' and NEW.actor=s.requested_by and NEW.reason=s.reason) or (NEW.action=s.status and NEW.actor=s.decided_by and NEW.reason=s.decision_reason))) then raise exception 'Audit must match selection state'; end if; return NEW; end $$;
create trigger benefit_audit_state before insert on benefit_selection_audit for each row execute function benefits_audit_state();
