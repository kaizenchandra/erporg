create table benefit_plan (
 tenant varchar(80) not null,code varchar(30) not null check(code ~ '^[A-Z0-9-]{2,30}$'),name varchar(120) not null check(length(trim(name))>0),
 currency varchar(3) not null check(currency in ('USD','GBP','EUR')),monthly_contribution numeric not null check(monthly_contribution>=0 and monthly_contribution<=999999.99 and scale(monthly_contribution)<=2),
 minimum_service_days integer not null check(minimum_service_days between 0 and 3650),effective_from date not null,created_by varchar(120) not null,idempotency_key varchar(80) not null,
 primary key(tenant,code),unique(tenant,created_by,idempotency_key)
);
create table benefit_plan_audit (
 id bigint generated always as identity primary key,tenant varchar(80) not null,code varchar(30) not null,actor varchar(120) not null,reason varchar(500) not null check(length(trim(reason))>0),
 recorded_at timestamptz not null,effective_from date not null,correlation_id varchar(80) not null,unique(tenant,code),foreign key(tenant,code) references benefit_plan(tenant,code)
);
create function benefits_immutable_configuration() returns trigger language plpgsql as $$ begin raise exception 'Benefits configuration is append only'; end $$;
create trigger immutable_benefit_plan before update or delete on benefit_plan for each row execute function benefits_immutable_configuration();
create trigger immutable_benefit_plan_audit before update or delete on benefit_plan_audit for each row execute function benefits_immutable_configuration();
