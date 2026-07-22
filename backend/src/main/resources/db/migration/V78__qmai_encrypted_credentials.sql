-- V78: ciphertext includes an IV and authentication tag, so sensitive values must not be constrained
-- to the legacy 200-character columns. Existing rows are deliberately not copied as clear text:
-- they must be re-saved with the deployment-managed encryption key.
alter table qmai_platform_config
  modify column open_id text not null,
  modify column grant_code text not null,
  modify column open_key text not null,
  modify column console_account text not null,
  modify column console_password text not null;
