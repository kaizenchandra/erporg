-- Payroll-owned durable intake. Source identifiers are published-contract references,
-- deliberately not cross-module reads, foreign keys or claims of calculated pay.
create table pay_talent_intake(tenant varchar(80) not null,id varchar(40) not null,source_id varchar(40) not null,snapshot jsonb not null,reason varchar(1000) not null,correlation_id varchar(80) not null,primary key(tenant,id),unique(tenant,source_id),check(snapshot->>'id'=id and snapshot->>'sourceId'=source_id),check(snapshot->>'status'='RECEIVED'),check(jsonb_typeof(snapshot->'awards')='array' and jsonb_array_length(snapshot->'awards') between 1 and 50));
create function pay_talent_immutable() returns trigger language plpgsql as $$begin raise exception 'Payroll compensation intake is retained and immutable'; end$$;
create trigger payroll_talent_history before update or delete or truncate on pay_talent_intake for each statement execute function pay_talent_immutable();
