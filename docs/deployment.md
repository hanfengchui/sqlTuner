# Deployment

## Production Requirements

- JDK 8
- Reachable Qwen or internal OpenAI-compatible LLM gateway
- Writable persistent data directory for state JSON
- No Node.js requirement on production hosts

## Environment Variables

```bash
SPRING_PROFILES_ACTIVE=prod
SQL_TUNER_DATA_DIR=/data/sql-tuning-assistant
LLM_PROVIDER=dashscope
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen-plus
DASHSCOPE_API_KEY=change-me-at-runtime
SQL_TUNER_ADMIN_PASSWORD=change-me-before-public-exposure
SQL_TUNER_USER_PASSWORD=change-me-before-public-exposure
```

Runtime state is written to `${SQL_TUNER_DATA_DIR}/sql-tuner-state.json`. Put this directory on persistent storage and include it in backups if tuning history and skill versions matter.
Model API keys can be provided either by `DASHSCOPE_API_KEY` or by the admin Model Config page. The page only sends the key to the backend; later reads only return whether a key is configured.

## Build

```bash
cd frontend
npm install
npm run build

cd ../backend
mkdir -p src/main/resources/static
cp -R ../frontend/dist/* src/main/resources/static/
mvn clean package
```

## Security Notes

- Do not commit API keys.
- Do not expose API keys to the frontend.
- Default accounts are only for local development.
- First production deployment must change default passwords or set `SQL_TUNER_ADMIN_PASSWORD` and `SQL_TUNER_USER_PASSWORD`.
