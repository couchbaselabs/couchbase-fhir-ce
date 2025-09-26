# HAPI FHIR - Local Load Test Setup

## Prerequisites

- Docker Desktop
- Python 3.11+ recommended
- pip and (optionally) a virtual environment

Dependencies are listed at repo root in `requirements.txt`.

---

## Start HAPI FHIR

This repository provides a compose file for HAPI at `fhir/docker-compose.hapi.yaml`.

```bash
# From repo root
docker compose -f fhir/docker-compose.hapi.yaml up -d
```

- HAPI FHIR Base URL: `http://localhost:8080/fhir`
- Postgres: `localhost:5432`

Verify server is up:

```bash
curl http://localhost:8080/fhir/metadata | head -c 400 | cat
```

Stop and clean (optional):

```bash
docker compose -f fhir/docker-compose.hapi.yaml down -v
```

---

## Install Python dependencies

```bash
# From repo root
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

---

## Run Locust against HAPI

Web UI:

```bash
locust -f fhir/locustfile.py --host http://localhost:8080/fhir
```

Open http://localhost:8089, set Users/Spawn rate, and start the test.

Headless examples:

```bash
# Simple 20-minute run
locust -f fhir/locustfile.py --host http://localhost:8080/fhir --headless -u 100 -r 10 -t 20m

# With CSV output
locust -f fhir/locustfile.py \
  --host http://localhost:8080/fhir \
  --headless -u 50 -r 5 -t 10m \
  --csv runs/hapi
```

---

## What the scenario does

`FHIRScenario` executes an end-to-end ophthalmology visit (registration → refraction → subjective refraction → PGP/PPG → lab → prescription), creating and reading FHIR resources along the way. Payloads are Faker-driven for realism, and missing dependencies (e.g., `Practitioner`, `Location`) are auto-created.

---

## Troubleshooting

- 404/metadata errors: Ensure `--host` includes `/fhir` (e.g., `http://localhost:8080/fhir`).
- Startup DB errors: HAPI may still be initializing; wait and retry.
- Reset environment: `docker compose -f fhir/docker-compose.hapi.yaml down -v` then bring back up.
- Port conflicts: Adjust ports in `fhir/docker-compose.hapi.yaml` if 8080/5432 are in use.
