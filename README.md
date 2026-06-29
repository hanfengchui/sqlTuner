# SQL Tuning Assistant

Internal SQL tuning assistant for slow OceanBase MySQL and OceanBase Oracle compatible SQL.

The platform accepts SQL plus manually supplied context, runs a deterministic rule scan, builds a versioned skill prompt, calls a configurable LLM provider, and returns structured optimization advice.

## Architecture

- Backend: Java 8, Spring Boot 2.7, Maven.
- Frontend: React, Vite, TypeScript, shadcn-style components, static build.
- Production: only JDK 8 is required to run the packaged backend.

## Quick Start

Backend:

```bash
cd backend
mvn test
mvn spring-boot:run
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Build frontend into backend static resources:

```bash
cd frontend
npm install
npm run build

cd ../backend
mkdir -p src/main/resources/static
cp -R ../frontend/dist/. src/main/resources/static/
mvn package
java -jar target/sql-tuning-assistant-0.1.0-SNAPSHOT.jar
```

LLM configuration uses environment variables:

```bash
LLM_PROVIDER=mock
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen-plus
DASHSCOPE_API_KEY=replace-at-runtime
SQL_TUNER_DATA_DIR=/data/sql-tuning-assistant
SQL_TUNER_ADMIN_PASSWORD=replace-before-public-deploy
SQL_TUNER_USER_PASSWORD=replace-before-public-deploy
```

Never commit real API keys.

## Current Scope

- Conversations, tuning tasks, skill versions, and editable model settings are stored in `SQL_TUNER_DATA_DIR/sql-tuner-state.json`.
- Default model provider is `mock`; configure `DASHSCOPE_API_KEY` and `LLM_PROVIDER=dashscope` or another OpenAI-compatible provider for real model calls.
- The first version is semi-automatic: users choose OceanBase MySQL or OceanBase Oracle, then paste SQL, schema, indexes, EXPLAIN, and business context manually. The service does not connect to production databases or execute SQL.
- Admin users can edit provider, base URL, model name, timeout, and API Key from the Model Config page. API keys are saved only in backend state and are never returned to the frontend in plaintext.

## Default Accounts

- Admin: `admin` / `admin123`
- User: `user` / `user123`

Change these before any real deployment, or set `SQL_TUNER_ADMIN_PASSWORD` and `SQL_TUNER_USER_PASSWORD`.
