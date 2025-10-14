# Project Overview

`couchbase-fhir-ce` aka Couchbase FHIR Server Community Edition is FHIR Server implementation for the FHIR R4 Release that uses Couchbase as the 
Database instead of RDBMS like Postgres or MySQL which are used traditionally. The idea behind this decision is that since FHIR is emitted and exchanged
as json and Couchbase supports JSON natively, a FHIR Server based on couchbase is better suited to the case. 

# Components

- **Backend**: The backend is built using Java 17 LTS, Spring Boot 3.5.0, Maven 3.9 and HAPI FHIR 8.2.0. This is the FHIR Server. The implementation is built on top
of HAPI FHIR and uses the Resource Validators, Parsers and Serializers from HAPI FHIR while using a custom implementation for DAOs and Database queries.

- **Admin UI**: A Basic Admin UI for the users of this Server. Features included are:
    - API Workbench for running FHIR Queries using the REST API
    - Monitoring: Stats about resource consumption like memory, cpu utiilization, disk utilization, qps etc, latency.
    - Buckets and Indexes: View Data in buckets, inpsect existing indexes or create new ones

The Admin UI is built using Material UI and React

# Folder Structure
- backend/ - all files for the FHIR Server
- frontend/ - all files for the React + Material UI Admin Dashboard

# Coding Style
- backend - use modern java features like streams, collections, pattern matching, type inference, optionals, etc. Peform robust error handling. Use camel case for variables and methods; use pascal case for classes.
- frontend - use tan-stack query for data fetching, create error boundaries and perform robust error handling. Use camel case for variables and Pascal Case for classes or constructor functions.

# Containerization
The [docker-compose.yaml](../docker-compose.yaml) file defines the service distribution in the containerized deployment of the application. Here's an overview:

## Services:
- `fhir-server`: The FHIR Server defined in the backend/ folder. The docker file is defined at [Dockerfile](../backend/Dockerfile)
- `fhir-admin`: The Admin Dashboard defined in the frontend/ folder. The docker file can be located at [Dockerfilr](../frontend/Dockerfile)
- `haproxy`: The Reverse Proxy that serves both the frontend and backend from port 80. It redirects all trafic for the /api and /fhir routes to the fhir-server and rest to the dashboard
- `couchbase`: Bundled Couhcbase Server image that the server connects to

## Volumes:
### Docker Managed
- couchbase-data: for persisting data stored on the couchbase server
- haproxy-socket: for mapping the haproxy socket volume to both the haproxy_service and fhir-server. This way the fhir-server will be able to query the haproxy dynamic api for stats

## Volume Mapping
- config.yaml: This is mapped from the project root to the fhir server container. 
- haproxy.cfg: Haproxy configuration file mounted to the haproxy configuration.
- docker.sock. Allows the container to query the hosts docker engine for ps stats.

## Configuration

- `config.yaml`: This file can be used to pass configuration to the server. It currently supports: connectionString (database hostna,e), username, password, servertype (Server/Capella - Managed Service) and sslEnabled and sslCertificate.

- init-docker-compose.sh: This script corrects the active docker socket on host if its not `/var/run/docker.sock`