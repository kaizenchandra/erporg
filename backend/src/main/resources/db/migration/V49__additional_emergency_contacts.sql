create table hr_contact_request (
 tenant varchar(80) not null,id varchar(40) not null,employee_id varchar(40) not null,
 effective_date date not null,reason varchar(500) not null check(length(trim(reason))>0),
 requested_by varchar(120) not null,requester_employee_id varchar(40) not null,employee_version bigint not null check(employee_version>=0),
 recorded_at timestamptz not null,correlation_id varchar(80) not null,idempotency_key varchar(80) not null,
 old_count integer not null check(old_count between 0 and 4),new_count integer not null check(new_count between 0 and 4),
 status varchar(20) not null check(status in('SUBMITTED','APPROVED','REJECTED')),version integer not null,
 decided_by varchar(120),reviewer_employee_id varchar(40),comment varchar(500),decided_at timestamptz,decision_correlation_id varchar(80),review_version bigint,decision_key varchar(80),
 primary key(tenant,id),unique(tenant,requested_by,idempotency_key),unique(tenant,decided_by,decision_key),
 foreign key(tenant,employee_id) references hr_employee(tenant,id),foreign key(tenant,requester_employee_id) references hr_employee(tenant,id),foreign key(tenant,reviewer_employee_id) references hr_employee(tenant,id),
 check((status='SUBMITTED' and version=0 and decided_by is null and reviewer_employee_id is null and comment is null and decided_at is null and decision_correlation_id is null and review_version is null and decision_key is null)
 or(status in('APPROVED','REJECTED') and version=1 and decided_by is not null and reviewer_employee_id is not null and decided_by<>requested_by and reviewer_employee_id<>employee_id and comment is not null and length(trim(comment))>0 and decided_at is not null and decided_at>=recorded_at and decision_correlation_id is not null and review_version is not null and review_version>=0 and decision_key is not null))
);
create unique index contact_pending on hr_contact_request(tenant,employee_id) where status='SUBMITTED';
create index contact_history on hr_contact_request(tenant,employee_id,recorded_at desc,id);
create table hr_contact_item (
 tenant varchar(80) not null,request_id varchar(40) not null,side varchar(3) not null check(side in('OLD','NEW')),ordinal integer not null check(ordinal between 1 and 4),
 name varchar(120) not null check(length(trim(name))>0 and name !~ '[[:cntrl:]]'),relationship varchar(60) not null check(length(trim(relationship))>0 and relationship !~ '[[:cntrl:]]'),phone varchar(30) not null check(phone ~ '^[+0-9 ()-]{7,30}$'),
 primary key(tenant,request_id,side,ordinal),foreign key(tenant,request_id) references hr_contact_request(tenant,id),unique(tenant,request_id,side,name,relationship,phone)
);
create table hr_contact_audit (
 tenant varchar(80) not null,request_id varchar(40) not null,status varchar(20) not null check(status in('SUBMITTED','APPROVED','REJECTED')),
 actor varchar(120) not null,reason varchar(500) not null,effective_date date not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,request_id,status),foreign key(tenant,request_id) references hr_contact_request(tenant,id)
);
create trigger immutable_contact_item before update or delete on hr_contact_item for each row execute function prevent_audit_change();
create trigger immutable_contact_audit before update or delete on hr_contact_audit for each row execute function prevent_audit_change();
create function guard_contact_request() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Contact history cannot be deleted'; end if;
 if TG_OP='INSERT' then
  if new.status<>'SUBMITTED' or not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.employee_version)
  or not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.requested_by and m.employee_id=new.requester_employee_id and(m.employee_id=new.employee_id or m.role='HR'))
  then raise exception 'Current owned contact submission required'; end if;
 else
  if old.status<>'SUBMITTED' or new.status not in('APPROVED','REJECTED') or
  row(new.tenant,new.id,new.employee_id,new.effective_date,new.reason,new.requested_by,new.requester_employee_id,new.employee_version,new.recorded_at,new.correlation_id,new.idempotency_key,new.old_count,new.new_count)
  is distinct from row(old.tenant,old.id,old.employee_id,old.effective_date,old.reason,old.requested_by,old.requester_employee_id,old.employee_version,old.recorded_at,old.correlation_id,old.idempotency_key,old.old_count,old.new_count)
  or not exists(select 1 from identity_membership m where m.tenant=new.tenant and m.subject=new.decided_by and m.employee_id=new.reviewer_employee_id and m.role='HR' and m.employee_id not in(new.employee_id,new.requester_employee_id))
  then raise exception 'Independent terminal contact decision required'; end if;
  if new.status='APPROVED' and (new.review_version<>new.employee_version or not exists(select 1 from hr_employee e where e.tenant=new.tenant and e.id=new.employee_id and e.version=new.review_version+1 and hr_employment_status(e.tenant,e.id,e.status,(new.decided_at at time zone 'UTC')::date)<>'Ended')) then raise exception 'Unchanged contact employee approval required'; end if;
 end if;
 return new;
end $$;
create trigger guard_contact_request before insert or update or delete on hr_contact_request for each row execute function guard_contact_request();
create function reconcile_contact_request() returns trigger language plpgsql as $$
declare r hr_contact_request;rid varchar;
begin
 if TG_TABLE_NAME='hr_contact_request' then rid=new.id;else rid=new.request_id;end if;
 select * into r from hr_contact_request where tenant=new.tenant and id=rid;
 if (select count(*) from hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and i.side='OLD')<>r.old_count
 or(select coalesce(max(ordinal),0) from hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and i.side='OLD')<>r.old_count
 or(select count(*) from hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and i.side='NEW')<>r.new_count
 or(select coalesce(max(ordinal),0) from hr_contact_item i where i.tenant=r.tenant and i.request_id=r.id and i.side='NEW')<>r.new_count
 or(select count(*) from hr_contact_audit a where a.tenant=r.tenant and a.request_id=r.id)<>(case when r.status='SUBMITTED' then 1 else 2 end)
 or not exists(select 1 from hr_contact_audit a where a.tenant=r.tenant and a.request_id=r.id and a.status='SUBMITTED' and a.actor=r.requested_by and a.reason=r.reason and a.effective_date=r.effective_date and a.recorded_at=r.recorded_at and a.correlation_id=r.correlation_id)
 or(r.status<>'SUBMITTED' and not exists(select 1 from hr_contact_audit a where a.tenant=r.tenant and a.request_id=r.id and a.status=r.status and a.actor=r.decided_by and a.reason=r.comment and a.effective_date=r.effective_date and a.recorded_at=r.decided_at and a.correlation_id=r.decision_correlation_id))
 then raise exception 'Contact snapshot/audit reconciliation failed'; end if;
 return null;
end $$;
create constraint trigger contact_request_reconciled after insert or update on hr_contact_request deferrable initially deferred for each row execute function reconcile_contact_request();
create constraint trigger contact_item_reconciled after insert on hr_contact_item deferrable initially deferred for each row execute function reconcile_contact_request();
create constraint trigger contact_audit_reconciled after insert on hr_contact_audit deferrable initially deferred for each row execute function reconcile_contact_request();
