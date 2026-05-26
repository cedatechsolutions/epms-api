# Railway Deployment

Use `api/api` as the Railway service root directory.

## Required Variables

Set these variables in the Railway API service:

```env
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=<long-random-secret-at-least-64-characters>
JWT_EXPIRATION_MS=86400000
APP_CORS_ALLOWED_ORIGINS=<deployed-frontend-origin>
APP_RECAPTCHA_ENABLED=true
APP_RECAPTCHA_SITE_SECRET=<google-recaptcha-secret>
SPRING_DATASOURCE_URL=<railway-postgres-jdbc-url>
SPRING_DATASOURCE_USERNAME=<railway-postgres-user>
SPRING_DATASOURCE_PASSWORD=<railway-postgres-password>
```

## Railway Settings

- Root directory: `api/api`
- Build command: `./mvnw -DskipTests clean package`
- Start command: `java -jar target/api-0.0.1-SNAPSHOT.jar`
- Health check path: `/actuator/health`

The `railway.json` file stores the build command, start command, restart policy, and health check path for config-as-code deployments.

## Production Notes

The `prod` profile disables Hibernate schema updates, SQL logging, the H2 console, SQL init scripts, and default bootstrap users.
Flyway applies versioned database migrations before Hibernate validates the schema. Review `DATABASE_OPERATIONS.md` before running production migrations or restoring backups.
