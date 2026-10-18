-- Expand terminal states without rewriting any resignation snapshot or earlier migration.
alter table hr_resignation drop constraint hr_resignation_status_check;
alter table hr_resignation add constraint hr_resignation_status_check check(status in('SUBMITTED','APPROVED','REJECTED','WITHDRAWN'));
alter table hr_resignation drop constraint hr_resignation_check;
alter table hr_resignation add constraint hr_resignation_check check(
 ((status='SUBMITTED' and version=0 or status='WITHDRAWN' and version=1) and decided_by is null and reviewer_employee_id is null and comment is null and decided_at is null and decision_key is null and decision_input_version is null)
 or(status in('APPROVED','REJECTED') and version=1 and decided_by is not null and decided_by<>requested_by and reviewer_employee_id is not null and reviewer_employee_id<>employee_id and comment is not null and length(trim(comment))>0 and decided_at is not null and decision_key is not null and decision_input_version=0)
);
alter table hr_resignation_audit drop constraint hr_resignation_audit_check;
alter table hr_resignation_audit add constraint hr_resignation_audit_check check((old_status is null and new_status='SUBMITTED') or(old_status='SUBMITTED' and new_status in('APPROVED','REJECTED','WITHDRAWN')));
create table hr_resignation_withdrawal (
 tenant varchar(80) not null,request_id varchar(40) not null,employee_id varchar(40) not null,actor varchar(120) not null,
 reason varchar(500) not null check(length(trim(reason))>0),input_version bigint not null check(input_version=0),new_version bigint not null check(new_version=1),
 recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 primary key(tenant,request_id),unique(tenant,actor,idempotency_key),
 foreign key(tenant,request_id) references hr_resignation(tenant,id),foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(actor) references identity_membership(subject)
);
create trigger immutable_resignation_withdrawal before update or delete on hr_resignation_withdrawal for each row execute function prevent_audit_change();
create or replace function protect_resignation_snapshot() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Resignation history cannot be deleted'; end if;
 if old.status<>'SUBMITTED' or new.status not in('APPROVED','REJECTED','WITHDRAWN') or new.version<>old.version+1 or
 row(new.tenant,new.id,new.employee_id,new.last_working_date,new.reason,new.requested_by,new.employee_version,new.recorded_at,new.idempotency_key)
 is distinct from row(old.tenant,old.id,old.employee_id,old.last_working_date,old.reason,old.requested_by,old.employee_version,old.recorded_at,old.idempotency_key)
 then raise exception 'Invalid resignation transition or snapshot mutation'; end if;
 return new;
end $$;
create function guard_resignation_withdrawal() returns trigger language plpgsql as $$
declare r hr_resignation;
begin
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id;
 if r.status<>'WITHDRAWN' or r.employee_id<>new.employee_id or r.requested_by<>new.actor or r.version<>new.new_version or new.recorded_at<r.recorded_at then raise exception 'Owned withdrawn resignation snapshot required'; end if;
 if not exists(
  select 1 from identity_membership m join hr_employee e on e.tenant=m.tenant and e.id=m.employee_id
  where m.subject=new.actor and m.tenant=new.tenant and m.employee_id=new.employee_id
  and hr_employment_status(e.tenant,e.id,e.status,(new.recorded_at at time zone 'UTC')::date)<>'Ended'
  and not exists(select 1 from hr_rehire_access_revocation x where x.subject=m.subject)
  and not exists(select 1 from hr_local_access_grant x where x.subject=m.subject and (x.tenant<>m.tenant or x.employee_id<>m.employee_id))
  and (not exists(select 1 from hr_employment_episode p where p.tenant=m.tenant and p.employee_id=m.employee_id)
   or exists(select 1 from hr_local_access_current g where g.tenant=m.tenant and g.employee_id=m.employee_id and g.subject=m.subject and g.effective_date<=(new.recorded_at at time zone 'UTC')::date))
 ) then raise exception 'Current requester identity required'; end if;
 return new;
end $$;
create trigger guard_resignation_withdrawal_insert before insert on hr_resignation_withdrawal for each row execute function guard_resignation_withdrawal();
create or replace function check_resignation_reconciliation() returns trigger language plpgsql as $$
declare r hr_resignation; w hr_resignation_withdrawal;
begin
 if TG_TABLE_NAME='hr_resignation' then
 select * into r from hr_resignation where tenant=new.tenant and id=new.id;
 else
 select * into r from hr_resignation where tenant=new.tenant and id=new.request_id;
 end if;
 select * into w from hr_resignation_withdrawal where tenant=r.tenant and request_id=r.id;
 if (select count(*) from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id)<>(case when r.status='SUBMITTED' then 1 else 2 end)
 or not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.old_status is null and a.new_status='SUBMITTED' and a.actor=r.requested_by and a.reason=r.reason and a.effective_date=r.last_working_date and a.recorded_at=r.recorded_at)
 or (r.status in('APPROVED','REJECTED') and not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.old_status='SUBMITTED' and a.new_status=r.status and a.actor=r.decided_by and a.reason=r.comment and a.effective_date=r.last_working_date and a.recorded_at=r.decided_at))
 or (r.status='WITHDRAWN') is distinct from (w.request_id is not null)
 or (r.status='WITHDRAWN' and not exists(select 1 from hr_resignation_audit a where a.tenant=r.tenant and a.request_id=r.id and a.old_status='SUBMITTED' and a.new_status='WITHDRAWN' and a.actor=w.actor and a.reason=w.reason and a.effective_date=r.last_working_date and a.recorded_at=w.recorded_at and a.correlation_id=w.correlation_id))
 or (r.status='APPROVED') is distinct from exists(select 1 from hr_resignation_handoff h join hr_resignation_audit a on a.tenant=h.tenant and a.request_id=h.request_id and a.new_status='APPROVED' and a.correlation_id=h.correlation_id and a.recorded_at=h.recorded_at where h.tenant=r.tenant and h.request_id=r.id and h.employee_id=r.employee_id and h.effective_date=r.last_working_date)
 then raise exception 'Resignation audit/handoff mismatch'; end if;
 return null;
end $$;
create constraint trigger resignation_withdrawal_reconciled after insert on hr_resignation_withdrawal deferrable initially deferred for each row execute function check_resignation_reconciliation();
