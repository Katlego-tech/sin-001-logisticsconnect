# HubServiceApp

## Overview

Serves provinces and sorting centers (place-name source of truth).

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

## Project structure

```
hub-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── HubServiceApp.java         routes
    │   ├── HubDirectory.java          lookup by ID or alias; provinces
    │   ├── IngestionClient.java       GET /hubs from ingestion-service
    │   ├── Hub.java                   this service's copy of a cleaned hub
    │   └── UpstreamUnavailable.java   a dependency couldn't be reached (503)
    └── test/java/co/wethinkcode/logisticsconnect/
```

## Build

```
mvn package
```

## Run

```
java -jar target/hub-service.jar
```

Listens on port `7051`. Its hub data comes from ingestion-service's `GET /hubs`, loaded on the
first request that needs it and kept; if ingestion-service is down, that request gets a 503 and
the next one tries again, so the services can be started in any order.

| Endpoint | Returns |
|---|---|
| `GET /hubs` | every hub, in ID order |
| `GET /hubs/{hubId}` | one hub, found by its ID **or any alias** (`H-510` answers with `H-500`), ignoring case; `404` if there is no such hub; `503` if ingestion-service can't be reached |
| `GET /provinces` | each province and the sorting centers in it |
| `GET /health` | `OK` |

| Variable | Default |
|---|---|
| `INGESTION_URL` | `http://localhost:7050` |

```
curl localhost:7051/hubs/H-510     # an alias: answers with H-500
curl localhost:7051/provinces
```

## Test

```
mvn test
```

Nothing else needs to be running: the directory is tested with in-memory data, the endpoints on
a random port, and the ingestion-service client against a stub HTTP server (status codes,
unreadable bodies, nothing listening). To check a running instance is up:

```
curl http://localhost:7051/health   # -> OK
```
