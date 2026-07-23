# PostgreSQL Init Scripts

Put local-only `.sql` or `.sh` initialization files here before the first `docker compose up`.

Docker runs these files only when the `postgres-data` volume is empty. Do not commit business data dumps, passwords, or production exports.
