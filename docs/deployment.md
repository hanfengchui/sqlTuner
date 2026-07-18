# Production Deployment

The rebuilt SQL Tuner runs as a single Spring Boot container with the React UI
bundled into the JAR. Runtime state is stored in MySQL through Flyway-managed
tables; the legacy JSON file is only an import source and must not be modified.

## Target Topology

- Public URL: `https://sql.pazhaug.info`
- Container bind: `127.0.0.1:18084:8080`
- Docker network: external `compose_app-network`
- Database host inside the network: `mysql`
- Runtime image tag: commit SHA
- Root filesystem: read-only with `/tmp` tmpfs
- Memory limit: `640m`

## Required Environment

```bash
SQL_TUNER_DB_USERNAME=sql_tuner
SQL_TUNER_DB_PASSWORD=change-me
SQL_TUNER_ADMIN_PASSWORD=change-me-strong-admin
SQL_TUNER_USER_PASSWORD=change-me-strong-user
SQL_TUNER_DATA_KEY=base64-encoded-32-byte-key
LLM_PROVIDER=dashscope
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen3.7-plus
LLM_REASONING_EFFORT=
DASHSCOPE_API_KEY=optional-bootstrap-key
SQL_TUNER_IMAGE_TAG=$(git rev-parse --short HEAD)
```

For the routine workload, `qwen3.7-plus` is the recommended cost/quality default.
Reserve `qwen3.7-max` for temporary deep-review or difficult-case evaluation;
plan screenshots should use `qwen3-vl-plus` through the separate vision model
setting. The application streams OpenAI-compatible text calls with
`stream=true`, but only a filtered `analysisNarrative` draft reaches browsers;
reasoning traces, raw JSON, rewrite SQL and index DDL remain hidden until the
final result passes strict validation.

Empty databases require strong admin/user passwords. Existing model settings and
encrypted API keys should be migrated from the legacy JSON state before serving
traffic.

The internal-network JDBC URL includes `allowPublicKeyRetrieval=true` because
MySQL 8 accounts use `caching_sha2_password`. The database remains reachable
only on the external Compose network and is not published to the Internet.

## Build And Run

```bash
docker compose -f docker-compose.sqltuner.yml build
docker compose -f docker-compose.sqltuner.yml up -d
curl -fsS http://127.0.0.1:18084/api/health/live
curl -fsS http://127.0.0.1:18084/api/health/ready
```

`Dockerfile` runs `npm ci`, `npm run build`, and Maven packaging so the served UI
always matches the backend commit.

## Nginx

Terminate TLS at Nginx and proxy to `http://127.0.0.1:18084`. The SSE endpoint
must disable buffering and use a long read timeout.

The repository contains the exact operational configurations:

- `deploy/nginx/sql.pazhaug.info.bootstrap.conf`: temporary HTTP site used to obtain the first certificate.
- `deploy/nginx/sql.pazhaug.info.conf`: production HTTPS site with SSE buffering disabled.
- `deploy/nginx/sql-tuner-8080-redirect.conf`: compatibility redirect for the old public port.
- `deploy/nginx/sql.pazhaug.info.rollback.conf`: HTTPS rollback upstream to the retained systemd service on `18081`.

```nginx
location /api/tuning/tasks/ {
    proxy_pass http://127.0.0.1:18084;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_buffering off;
    proxy_read_timeout 600s;
}

location / {
    proxy_pass http://127.0.0.1:18084;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;
}
```

After HTTPS smoke tests pass, the old `http://64.188.8.30:8080` entry should
return a `308` redirect to `https://sql.pazhaug.info`.

## Rollback

Rollback leaves the MySQL data in place for later repair:

1. Stop the new Compose service.
2. Restore the previous Nginx upstream to `127.0.0.1:18081`.
3. Re-enable the original `sql-tuner.service`.
4. Keep the legacy JSON backup untouched.

The old runtime is an external rollback dependency and remains owned by the host:
`sql-tuner.service`, `/opt/sql-tuner/sql-tuner.jar`, `/etc/sql-tuner/sql-tuner.env`,
and `127.0.0.1:18081`. Replace the active SQL domain site with
`deploy/nginx/sql.pazhaug.info.rollback.conf`, run `nginx -t`, reload Nginx,
then stop the Compose service. The checked-in rollback config makes the proxy
switch reproducible while the existing host unit and frozen JSON remain intact.
