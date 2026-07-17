# MySQL Persistence Migration Notes

The rebuilt backend uses Flyway-managed MySQL tables instead of the legacy single JSON state file.

Required environment variables for an empty database:

- `SQL_TUNER_DB_URL`
- `SQL_TUNER_DB_USERNAME`
- `SQL_TUNER_DB_PASSWORD`
- `SQL_TUNER_ADMIN_PASSWORD` and `SQL_TUNER_USER_PASSWORD`, each at least 12 characters
- `SQL_TUNER_DATA_KEY`, containing exactly 32 random bytes encoded as Base64,
  for encrypting model API keys

The application refuses to bootstrap an empty user table without strong passwords. Existing JSON state should be kept read-only during migration; the JSON file is not modified by the new runtime.
