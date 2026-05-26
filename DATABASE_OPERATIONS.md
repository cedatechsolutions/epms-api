# Database Operations

## Schema Migrations

Database schema changes are managed by Flyway migrations in `src/main/resources/db/migration`.

- Add every schema change as a new versioned migration, for example `V2__add_audit_logs.sql`.
- Do not edit an existing migration after it has been applied to a shared or production database.
- Hibernate is configured with `spring.jpa.hibernate.ddl-auto=validate`, so the app verifies that entity mappings match the migrated database instead of changing tables at startup.
- CI runs `./mvnw clean test`, which starts the test database, applies Flyway migrations, and validates the JPA mappings.

## Existing Databases

The initial migration assumes an empty database. For an existing database that already has the `users`, `roles`, and `user_roles` tables but no Flyway history table:

1. Back up the database first.
2. Compare the live schema with `V1__initial_schema.sql`.
3. If the schemas match, set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` once to let Flyway mark the existing schema as version 1.
4. Remove that variable after the baseline has been recorded.

Do not enable baselining by default in production. It can hide a schema that has drifted from the expected migration state.

## Backup Policy

For production, use managed PostgreSQL backups from the hosting provider plus periodic manual export before risky changes.

- Automated backups: daily.
- Retention: at least 7 daily backups and 4 weekly backups.
- Manual backup: before production migrations, bulk imports, destructive maintenance, or major releases.
- Storage: keep backups encrypted and access-limited.
- Naming: include environment, database name, and UTC timestamp.

Example logical backup command:

```bash
pg_dump "$DATABASE_URL" --format=custom --file="cems-prod-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

## Restore Procedure

1. Create or select a non-production restore target.
2. Restore the backup into the target database.
3. Start the API against the restored database with `SPRING_PROFILES_ACTIVE=prod`.
4. Confirm `/actuator/health` returns healthy.
5. Smoke test login and key admin user-management flows.
6. Record the backup file, restore timestamp, target database, result, and any issues found.

Example logical restore command:

```bash
pg_restore --clean --if-exists --dbname "$RESTORE_DATABASE_URL" "cems-prod-YYYYMMDDTHHMMSSZ.dump"
```

## Restore Testing

Run a restore test at least monthly and before relying on a new backup process. A backup is not considered valid until it has been restored successfully and the application has passed smoke testing against it.
