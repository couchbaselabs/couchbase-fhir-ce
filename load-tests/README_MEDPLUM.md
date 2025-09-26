# Medplum FHIR - Local Load Test Setup

## Prerequisites

- Docker Desktop
- Python + virtualenv (for Locust)

## Start Medplum stack

This repository provides a compose file at `fhir/docker-compose.medplum.yml`.

```bash
# From repo root
docker compose -f fhir/docker-compose.medplum.yml up -d
```

- Medplum App (UI): `http://localhost:3003`
- Medplum FHIR Base URL: `http://localhost:8103/fhir/R4`
- Healthcheck:

```bash
curl http://localhost:8103/healthcheck
```

## Authentication (client credentials)

1. Open UI (http://localhost:3003) and login: `admin@example.com` / `medplum_admin`.
2. Project → Clients → New Client (Confidential).
3. Go to New Client after creation.
4. Copy clientId and clientSecret.
5. Replace the clientId and clientSecret in .env.medplum

Export env and run Locust:

```bash
locust -f fhir/locustfile.py --host "http://localhost:8103/fhir/R4"
```

Headless example:

```bash
locust -f fhir/locustfile.py --host "http://localhost:8103/fhir/R4" --headless -u 100 -r 10 -t 20m
```

## Notes

- Compose runs Postgres (mapped to host 5434) and Redis for Medplum.
- Keep HAPI and Medplum on separate DBs/containers to avoid interference.
