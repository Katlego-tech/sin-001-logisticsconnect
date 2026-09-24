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
    │   ├── StageView.java             ...answered from events received (the default)
    │   ├── StageSubscriber.java       durable subscription feeding the view
    │   ├── StageChanged.java          the event, as this service reads it
    │   ├── RestStageSource.java       ...or asked of delay-stage-service (STAGE_SOURCE=rest)
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

Listens on port `7053`. For each ETA it asks hub-service which hub the ID names, then reads that
hub's stage from its own **stage view**: a copy of every hub's stage, built from the events on
`package-status-topic` and saved to `data/stage-view.json`. No call to delay-stage-service is
made, so ETAs keep working while it's down.

- The subscription is **durable**: while this service is down the broker keeps the events for
  it, and it catches up when it returns. Only changes made before its very first start are
  never replayed; for those hubs the ETA says the stage is assumed (`"stageKnown": false`).
- It connects to the broker in the background and keeps retrying, so it starts (and answers)
  even when the broker isn't up yet.
- `STAGE_SOURCE=rest` puts back the stage-2 wiring: a REST call to delay-stage-service on every
  ETA.

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
| `STAGE_SOURCE` | `mq` (the topic); `rest` for a call per ETA; anything else stops startup |
| `STAGE_VIEW_FILE` | `data/stage-view.json` |
| `DELAY_STAGE_URL` | `http://localhost:7052` (only with `STAGE_SOURCE=rest`) |

```
curl localhost:7053/eta/H-503
```

## Test

```
mvn test
```

Nothing else needs to be running: the ETA model and the stage view are tested directly
(including a restart from the saved file), the endpoint on a random port with its dependencies
faked, both HTTP clients against stub servers, and the subscription against a real in-process
ActiveMQ broker (no Docker), including an event published while it was disconnected. To check a running instance is up:

```
curl http://localhost:7053/health   # -> OK
```
