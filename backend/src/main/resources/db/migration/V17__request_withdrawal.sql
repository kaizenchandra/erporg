alter table hr_request drop constraint hr_request_status_check;
alter table hr_request drop constraint hr_request_check;
alter table hr_request add constraint hr_request_status_check check(status in ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN'));
alter table hr_request add constraint hr_request_decision_actor_check check((status='SUBMITTED' and decided_by is null) or (status in ('APPROVED','REJECTED') and decided_by is not null and decided_by<>requested_by) or (status='WITHDRAWN' and decided_by=requested_by));
create table hr_request_withdrawal (
 tenant varchar(80) not null,request_id varchar(40) primary key,actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),idempotency_key varchar(80) not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 unique(tenant,actor,idempotency_key),foreign key(request_id) references hr_request(id)
);
create trigger immutable_request_withdrawal before update or delete on hr_request_withdrawal for each row execute function prevent_audit_change();
create function validate_request_withdrawal() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from hr_request r where r.tenant=new.tenant and r.id=new.request_id and r.status='WITHDRAWN' and r.requested_by=new.actor and r.decided_by=new.actor and r.comment=new.reason) then raise exception 'Withdrawal must match requester-owned withdrawn request'; end if; return new; end $$;
create trigger consistent_request_withdrawal before insert on hr_request_withdrawal for each row execute function validate_request_withdrawal();
