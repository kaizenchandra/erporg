create table hr_resignation (
 tenant varchar(80) not null, id varchar(40) not null, employee_id varchar(40) not null,
 last_working_date date not null, reason varchar(500) not null check(length(trim(reason))>0),
 status varchar(20) not null check(status in ('SUBMITTED','APPROVED','REJECTED')),
 requested_by varchar(120) not null, employee_version bigint not null check(employee_version>=0),
 recorded_at timestamptz not null, version bigint not null check(version in(0,1)),
 idempotency_key varchar(80) not null, decided_by varchar(120), reviewer_employee_id varchar(40),
 comment varchar(500), decided_at timestamptz, decision_key varchar(80), decision_input_version bigint,
 primary key(tenant,id), unique(tenant,requested_by,idempotency_key),
 foreign key(tenant,employee_id) references hr_employee(tenant,id),
 foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id),
 check((status='SUBMITTED' and version=0 and decided_by is null and reviewer_employee_id is null and comment is null and decided_at is null and decision_key is null and decision_input_version is null)
 or(status in('APPROVED','REJECTED') and version=1 and decided_by is not null and decided_by<>requested_by and reviewer_employee_id is not null and reviewer_employee_id<>employee_id and comment is not null and length(trim(comment))>0 and decided_at is not null and decision_key is not null and decision_input_version=0))
);
create unique index hr_resignation_outstanding on hr_resignation(tenant,employee_id) where status in('SUBMITTED','APPROVED');
create index hr_resignation_page on hr_resignation(tenant,recorded_at desc,id);
create table hr_resignation_audit (
 id bigint generated always as identity primary key, tenant varchar(80) not null, request_id varchar(40) not null,
 old_status varchar(20), new_status varchar(20) not null, actor varchar(120) not null,
 reason varchar(500) not null, effective_date date not null, recorded_at timestamptz not null, correlation_id varchar(80) not null,
 foreign key(tenant,request_id) references hr_resignation(tenant,id),
 unique(tenant,request_id,new_status),
 check((old_status is null and new_status='SUBMITTED') or(old_status='SUBMITTED' and new_status in('APPROVED','REJECTED')))
);
create trigger immutable_resignation_audit before update or delete on hr_resignation_audit for each row execute function prevent_audit_change();
create table hr_resignation_handoff (
 tenant varchar(80) not null, request_id varchar(40) not null, employee_id varchar(40) not null,
 effective_date date not null, recorded_at timestamptz not null, correlation_id varchar(80) not null,
 status varchar(30) not null check(status='PENDING_EXIT_COORDINATION'), primary key(tenant,request_id),
 foreign key(tenant,request_id) references hr_resignation(tenant,id), foreign key(tenant,employee_id) references hr_employee(tenant,id)
);
create trigger immutable_resignation_handoff before update or delete on hr_resignation_handoff for each row execute function prevent_audit_change();
create function protect_resignation_snapshot() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Resignation history cannot be deleted'; end if;
 if old.status<>'SUBMITTED' or new.status not in('APPROVED','REJECTED') or new.version<>old.version+1 or
 row(new.tenant,new.id,new.employee_id,new.last_working_date,new.reason,new.requested_by,new.employee_version,new.recorded_at,new.idempotency_key)
 is distinct from row(old.tenant,old.id,old.employee_id,old.last_working_date,old.reason,old.requested_by,old.employee_version,old.recorded_at,old.idempotency_key)
 then raise exception 'Invalid resignation transition or snapshot mutation'; end if;
 return new;
end $$;
create trigger protect_resignation before update or delete on hr_resignation for each row execute function protect_resignation_snapshot();
create function check_resignation_reconciliation() returns trigger language plpgsql as $$
declare r hr_resignation;
begin
 if TG_TABLE_NAME='hr_resignation' then
 select * into r from hr_resignation where tenant=new.tenant and id=new.id;
 else
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id;
 end if;
 if not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.new_status='SUBMITTED' and a.actor=r.requested_by and a.reason=r.reason and a.effective_date=r.last_working_date)
 or (r.status<>'SUBMITTED' and not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.new_status=r.status and a.actor=r.decided_by and a.reason=r.comment and a.effective_date=r.last_working_date))
 or (r.status='APPROVED') is distinct from exists(select 1 from hr_resignation_handoff h where h.tenant=r.tenant and h.request_id=r.id and h.employee_id=r.employee_id and h.effective_date=r.last_working_date)
 then raise exception 'Resignation audit/handoff mismatch'; end if;
 return null;
end $$;
create constraint trigger resignation_reconciled after insert or update on hr_resignation deferrable initially deferred for each row execute function check_resignation_reconciliation();
create constraint trigger resignation_handoff_reconciled after insert on hr_resignation_handoff deferrable initially deferred for each row execute function check_resignation_reconciliation();
