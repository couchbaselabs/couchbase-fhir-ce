# Docker Deployment Guide

This guide describes how to run the project using Docker and Docker Compose.

## 1. Prepare Configuration

The backend service requires a `config.yaml` file, which is mounted into the container. To create it:

```bash
cp config.yaml.template config.yaml
```
Edit `config.yaml` as needed for your environment.

~~## 2. Set the Docker Socket Mount~~

~~The backend service (`fhir-server`) needs access to the Docker socket. The correct socket path may vary depending on your Docker context. Use the provided script to automatically detect and update the mount:~~

```bash
❌ ./init-docker-compose.sh
```
~~This will update `docker-compose.yaml` to mount the correct Docker socket into the `fhir-server` container.~~

## 3. Build and Run the Containers

Build all services:

> **Important**: For running locally, use the `docker-compose.local.yaml` file explicitly. <br/>
> Explicitly pass the file using the -f flag for all commands listed below <br>
> e.g.   `docker compose -f docker-compose.local.yaml up|down|build`


```bash
docker compose build
```

Start all services in the background:

```bash
docker compose up -d
```

To view logs for all services:

```bash
docker compose logs -f
```

To stop all services:

```bash
docker compose down
```

To stop and remove all services, networks, and named volumes (including data):

> Warning❗: If you run this command, all the data, including the data in the local couchbase cluster will be deleted. <br/>
> Before running the server again, you'll have to initialize the couchbase cluster and create buckets

<br/>



```bash
docker compose down -v
```

## 4. Additional Commands

- To rebuild a specific service:
  ```bash
  docker compose build <service-name>
  ```
- To restart a specific service:
  ```bash
  docker compose restart <service-name>
  ```

## 5. First-Time Setup of Couchbase Server

When running Couchbase Server for the first time, you need to initialize the cluster and set up the administrator account. Follow these steps:


1. **Access the Couchbase Web Console:**
   - Open your browser and go to [http://localhost:8091](http://localhost:8091)
   - You will see the Couchbase setup wizard.

2. **Initialize the cluster and set the administrator account:**
   - Walk through the setup wizard.
   - Set the **username** and **password** for the administrator account when prompted. (Remember these credentials; you will need them to access and manage Couchbase.)
   - Accept the default values or adjust settings as needed for your environment.
   - create a bucket named `fhir`

For more details, see the [Couchbase Docker documentation](https://hub.docker.com/_/couchbase) and [official setup guide](https://docs.couchbase.com/server/current/install/getting-started-docker.html).

---

For more details, see the main `README.md`.
