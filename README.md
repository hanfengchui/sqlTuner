# SQL Tuning Assistant

Internal SQL tuning assistant for slow OceanBase MySQL and OceanBase Oracle compatible SQL.

The platform accepts pasted SQL or a complete inspection-report text block plus optional screenshots, runs a deterministic rule scan, builds a versioned skill prompt, calls a configurable LLM provider, and returns concise, validated optimization advice in the conversation.

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
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cp -R ../frontend/dist/. src/main/resources/static/
mvn clean package
java -jar target/sql-tuning-assistant-0.1.0-SNAPSHOT.jar
```

The clean build prevents deleted Spring components from surviving as stale bytecode in the deployable JAR.

LLM configuration uses environment variables:

```bash
LLM_PROVIDER=mock
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen-plus
LLM_REASONING_EFFORT=
DASHSCOPE_API_KEY=replace-at-runtime
SQL_TUNER_DB_URL='jdbc:mysql://mysql:3306/sql_tuner?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
SQL_TUNER_DB_USERNAME=sql_tuner
SQL_TUNER_DB_PASSWORD=replace-before-running
SQL_TUNER_DATA_KEY=base64-encoded-32-byte-key
SQL_TUNER_ADMIN_PASSWORD=replace-before-public-deploy
SQL_TUNER_USER_PASSWORD=replace-before-public-deploy
```

Never commit real API keys.

## Current Scope

- Conversations, tuning tasks, artifacts, input images, skill versions, and editable model settings are stored transactionally in MySQL. Flyway applies the schema on startup.
- Default model provider is `mock`; configure `DASHSCOPE_API_KEY` and `LLM_PROVIDER=dashscope` or another OpenAI-compatible provider for real model calls.
- `LLM_REASONING_EFFORT` is optional. Set `low`, `medium`, `high`, or `xhigh` only when the configured OpenAI-compatible gateway supports Chat Completions `reasoning_effort`; leave it empty for ordinary providers.
- Users choose OceanBase MySQL or OceanBase Oracle, then paste a single SQL statement or complete inspection-report text. Optional PNG/JPEG/WebP screenshots can be attached. The server extracts parseable SQL, EXPLAIN, statistics and metrics from a pasted report; DOC/DOCX/PDF import is intentionally unsupported.
- The service does not connect to production databases, execute SQL, or apply DDL. Missing evidence lowers the confidence ceiling and blocks deterministic rewrite/index DDL output.
- Admin users can edit provider, base URL, model name, timeout, and API Key from the Model Config page. API keys are encrypted with `SQL_TUNER_DATA_KEY` and are never returned to the frontend in plaintext.

## Initial Accounts

An empty database is initialized with `admin` and `user` only when both `SQL_TUNER_ADMIN_PASSWORD` and `SQL_TUNER_USER_PASSWORD` are present and at least 12 characters long. There are no built-in fallback passwords.
