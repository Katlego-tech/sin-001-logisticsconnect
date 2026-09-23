# DelayStageServiceApp

## Overview

Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns).

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## Project structure

```
delay-stage-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── DelayStageServiceApp.java  routes; validates the request body
    │   ├── DelayStages.java           each hub's stage, 0-8
    │   ├── HubClient.java             GET /hubs/{hubId} from hub-service
    │   ├── HubLookup.java             "which hub does this ID name?"
    │   ├── Hub.java                   the part of hub-service's record this service uses
    │   ├── UpstreamUnavailable.java   a dependency couldn't be reached (503)
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
```

## Build

```
mvn package
```

## Run

```
java -jar target/delay-stage-service.jar
```

Listens on port `7052`. Stages are kept in memory, under each hub's canonical ID.

| Endpoint | Returns |
|---|---|
| `POST /delay-stage/{hubId}` with `{"stage": 3}` | the change: `hubId` (canonical), `stage`, `previousStage`, `updatedAt`, and `changed` (false if the hub was already at that stage) |
| `GET /delay-stage/{hubId}` | the hub's current stage; `updatedAt` is `null` if it was never set (stage 0) |
| `GET /delay-stage` | every hub whose stage has been set |
| `GET /health` | `OK` |

Both `{hubId}` endpoints ask hub-service which hub the ID names, so an alias works
(`H-504` is `H-500`), and they answer:

- `400` if the body isn't `{"stage": n}` with a whole number `n` from 0 to 8
- `404` if hub-service knows no such hub
- `503` if hub-service can't be reached

| Variable | Default |
|---|---|
| `HUB_SERVICE_URL` | `http://localhost:7051` |

```
curl -X POST localhost:7052/delay-stage/H-504 -d '{"stage": 5}'   # recorded under H-500
curl localhost:7052/delay-stage/H-500
```

## Test

```
mvn test
```

Nothing else needs to be running: the stage store is tested directly, the endpoints on a random
port with hub-service faked, and the hub-service client against a stub HTTP server. To check a
running instance is up:

```
curl http://localhost:7052/health   # -> OK
```
