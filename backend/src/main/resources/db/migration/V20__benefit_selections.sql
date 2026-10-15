create table benefit_selection (
 tenant varchar(80) not null,id varchar(36) not null,employee_id varchar(80) not null,plan_code varchar(30) not null,
 plan_name varchar(120) not null,currency varchar(3) not null check(currency in ('USD','GBP','EUR')),
 monthly_contribution numeric not null check(monthly_contribution between 0 and 999999.99 and scale(monthly_contribution)<=2),
 rule_version varchar(80) not null check(rule_version='DEMO-FIXED-MONTHLY-v1'),effective_date date not null,
 requested_by varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),idempotency_key varchar(80) not null,
 status varchar(10) not null default 'PENDING' check(status in ('PENDING','APPROVED','REJECTED')),version bigint not null default 0,
 decided_by varchar(120),decision_key varchar(80),decision_reason varchar(500),recorded_at timestamptz not null,
 primary key(tenant,id),unique(tenant,requested_by,idempotency_key),unique(tenant,decided_by,decision_key),
 foreign key(tenant,plan_code) references benefit_plan(tenant,code),
 check((status='PENDING' and version=0 and decided_by is null and decision_key is null and decision_reason is null) or (status<>'PENDING' and version=1 and decided_by is not null and decision_key is not null and length(trim(decision_reason))>0))
);
create unique index benefit_one_active_plan on benefit_selection(tenant,employee_id,plan_code) where status in ('PENDING','APPROVED');
create table benefit_dependant (
 tenant varchar(80) not null,selection_id varchar(36) not null,ordinal integer not null check(ordinal between 0 and 4),name varchar(120) not null check(length(trim(name))>0),relationship varchar(10) not null check(relationship in ('PARTNER','CHILD','OTHER')),
 primary key(tenant,selection_id,ordinal),foreign key(tenant,selection_id) references benefit_selection(tenant,id)
);
create table benefit_selection_audit (
 id bigint generated always as identity primary key,tenant varchar(80) not null,selection_id varchar(36) not null,action varchar(10) not null check(action in ('SUBMITTED','APPROVED','REJECTED')),actor varchar(120) not null,reason varchar(500) not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 unique(tenant,selection_id,action),foreign key(tenant,selection_id) references benefit_selection(tenant,id)
);
create table benefit_deduction_handoff (
 tenant varchar(80) not null,selection_id varchar(36) not null,employee_id varchar(80) not null,currency varchar(3) not null,monthly_contribution numeric not null check(monthly_contribution between 0 and 999999.99 and scale(monthly_contribution)<=2),effective_date date not null,rule_version varchar(80) not null,status varchar(20) not null default 'PENDING_PAYROLL' check(status='PENDING_PAYROLL'),recorded_at timestamptz not null,
 primary key(tenant,selection_id),foreign key(tenant,selection_id) references benefit_selection(tenant,id)
);
create function benefits_selection_transition() returns trigger language plpgsql as $$ begin
 if TG_OP='DELETE' then raise exception 'Benefit history cannot be deleted'; end if;
 if OLD.status<>'PENDING' or NEW.status not in ('APPROVED','REJECTED') or NEW.version<>OLD.version+1 or
 (to_jsonb(NEW)-array['status','version','decided_by','decision_key','decision_reason'])<>(to_jsonb(OLD)-array['status','version','decided_by','decision_key','decision_reason']) then raise exception 'Invalid benefit selection transition'; end if;
 return NEW; end $$;
create trigger benefit_selection_transition before update or delete on benefit_selection for each row execute function benefits_selection_transition();
create trigger immutable_benefit_dependant before update or delete on benefit_dependant for each row execute function benefits_immutable_configuration();
create trigger immutable_benefit_selection_audit before update or delete on benefit_selection_audit for each row execute function benefits_immutable_configuration();
create trigger immutable_benefit_deduction before update or delete on benefit_deduction_handoff for each row execute function benefits_immutable_configuration();
create function benefits_handoff_snapshot() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from benefit_selection s where s.tenant=NEW.tenant and s.id=NEW.selection_id and s.status='APPROVED' and s.employee_id=NEW.employee_id and s.currency=NEW.currency and s.monthly_contribution=NEW.monthly_contribution and s.effective_date=NEW.effective_date and s.rule_version=NEW.rule_version) then raise exception 'Deduction must match approved selection'; end if; return NEW; end $$;
create trigger benefit_handoff_snapshot before insert on benefit_deduction_handoff for each row execute function benefits_handoff_snapshot();
