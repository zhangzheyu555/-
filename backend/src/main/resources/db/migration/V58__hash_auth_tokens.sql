-- Existing sessions stay valid, but only their SHA-256 digests remain in the database.
alter table auth_token add column token_hash char(64) null after token;

update auth_token
set token_hash = lower(sha2(token, 256))
where token_hash is null;

alter table auth_token modify column token_hash char(64) not null;
alter table auth_token drop primary key, drop column token, add primary key (token_hash);
