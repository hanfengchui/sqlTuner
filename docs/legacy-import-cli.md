# Legacy JSON Import

The legacy importer is disabled by default. It reads the old
`sql-tuner-state.json`, validates it, and imports it into the Flyway-managed
MySQL schema without modifying the source JSON file.

## Dry Run

```bash
java -jar sql-tuner.jar \
  --spring.main.web-application-type=none \
  --app.legacy-import.enabled=true \
  --app.legacy-import.dry-run=true \
  --app.legacy-import.path=/var/lib/sql-tuner/sql-tuner-state.json \
  --app.legacy-import.admin-password="$SQL_TUNER_ADMIN_PASSWORD" \
  --app.legacy-import.user-password="$SQL_TUNER_USER_PASSWORD"
```

Dry-run parses the JSON, computes SHA-256, validates passwords, and reports
object counts. It does not write users, tasks, model config, or migration
records.

## Import

```bash
java -jar sql-tuner.jar \
  --spring.main.web-application-type=none \
  --app.legacy-import.enabled=true \
  --app.legacy-import.dry-run=false \
  --app.legacy-import.path=/var/lib/sql-tuner/sql-tuner-state.json \
  --app.legacy-import.admin-password="$SQL_TUNER_ADMIN_PASSWORD" \
  --app.legacy-import.user-password="$SQL_TUNER_USER_PASSWORD"
```

Import runs in one database transaction:

- Creates or updates user ID `1` (`admin`) and ID `2` (`user`) using BCrypt.
- Preserves conversation, message, task, and skill IDs and timestamps.
- Stores task JSON and artifact payloads in JSON columns.
- Re-encrypts the imported model API key through `SQL_TUNER_DATA_KEY`.
- Writes the source SHA-256 and imported counts into `migration_records`.

Repeating the same file is a no-op. A different source after a successful import
fails safely and does not overwrite existing migrated data.
