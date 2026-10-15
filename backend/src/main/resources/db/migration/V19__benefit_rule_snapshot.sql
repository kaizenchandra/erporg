alter table benefit_plan add column rule_version varchar(80) not null default 'DEMO-FIXED-MONTHLY-v1' check(rule_version='DEMO-FIXED-MONTHLY-v1');
