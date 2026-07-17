-- Keep H2 test sessions compatible with the MySQL SHA-256 migration.
alter table auth_token add column token_hash varchar(64);

update auth_token
set token_hash = lower(rawtohex(hash('SHA-256', stringtoutf8(token))))
where token_hash is null;

alter table auth_token alter column token_hash set not null;
alter table auth_token drop primary key;
alter table auth_token drop column token;
alter table auth_token add primary key (token_hash);
