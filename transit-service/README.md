# TransitServiceApp

## Overview

Calculates estimated arrival windows based on hub and delay stage.

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## Project structure

```
transit-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── TransitServiceApp.java     routes
    │   ├── EtaCalculator.java         the ETA model and its assumptions
    │   ├── StageSource.java           "what stage is this hub at?"
    │   ├── RestStageSource.java       ...asked of delay-stage-service over REST
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
java -jar target/transit-service.jar
```

Listens on port `7053`. For each ETA it asks hub-service which hub the ID names, then asks
delay-stage-service for that hub's current stage.

| Endpoint | Returns |
|---|---|
| `GET /eta/{hubId}` | the estimate: `status` (`ON_TIME`, `DELAYED`, `SUSPENDED`, `HUB_INACTIVE`), the `stage` it used, `earliestHours`/`latestHours` and the matching `earliestArrival`/`latestArrival` instants (all `null` when there is no window), and `warnings` listing every assumption the estimate rests on. `404` if there is no such hub; `503` naming whichever service couldn't be reached |
| `GET /health` | `OK` |

The legacy data has no transit times, so the service levels are assumptions, all in
`EtaCalculator`: metro hubs (Gauteng, Western Cape, KwaZulu-Natal) 24–48h, others 48–96h; each
delay stage adds 12h to the earliest arrival and 24h to the latest; stage 8 is a shutdown
(`SUSPENDED`), and an inactive hub gets no window (`HUB_INACTIVE`).

| Variable | Default |
|---|---|
| `HUB_SERVICE_URL` | `http://localhost:7051` |
| `DELAY_STAGE_URL` | `http://localhost:7052` |

```
curl localhost:7053/eta/H-503
```

## Test

```
mvn test
```

Nothing else needs to be running: the ETA model is tested directly, the endpoint on a random
port with its two dependencies faked, and both clients against stub HTTP servers (status codes,
unreadable or malformed replies, nothing listening). To check a running instance is up:

```
curl http://localhost:7053/health   # -> OK
```
