# LogisticsConnect

## Overview

Supply chain parcel delivery hub and transit delay tracking.

Domain entities: hubs, sorting centers, regional districts.

Every class in this repo lives in a single flat package, `co.wethinkcode.logisticsconnect`. LogisticsConnect is built
as a small set of independent services, following a growth path from simple data
cleanup through synchronous REST calls to asynchronous MQ decoupling and alerting:

1. clean a messy legacy CSV export (`hubs-global.csv`) — handled by **IngestionServiceApp**
2. serve it up and act on it, via three REST services calling each other directly
   over HTTP
3. decouple the relevant services with an ActiveMQ topic (`package-status-topic`) instead of
   direct calls — shared broker setup lives in [`common/`](common)
4. raise the alarm on failure — handled by **AlertBotApp**

| Service | Folder | Port | Role |
|---|---|---|---|
| IngestionServiceApp | [`ingestion-service/`](ingestion-service) | 7050 | Parses and cleans `hubs-global.csv` |
| HubServiceApp | [`hub-service/`](hub-service) | 7051 | Serves provinces and sorting centers (place-name source of truth). |
| DelayStageServiceApp | [`delay-stage-service/`](delay-stage-service) | 7052 | Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns). |
| TransitServiceApp | [`transit-service/`](transit-service) | 7053 | Calculates estimated arrival windows based on hub and delay stage. |
| AlertBotApp | [`alertbot/`](alertbot) | 7054 | posts proactive delay notifications to public transit social media pages (simulated). |

Plus [`common/`](common) (no port) — the shared ActiveMQ broker and MQ config notes
for `package-status-topic`: Package status updates move from latency-driven RPC to bandwidth-driven messaging.

**Status:** all four stages are implemented and verified end to end against the broker:
`hubs-global.csv` is cleaned and served, hub-service, delay-stage-service and transit-service
work over REST, and stage changes reach transit-service through `package-status-topic` instead
of a direct call. `alertbot` (stage 4) subscribes to the same topic and posts simulated delay
alerts when a hub crosses a threshold.
[IMPLEMENTATION.md](IMPLEMENTATION.md) covers the decisions and what was checked.

## Your task

Implement the four stages below, in order — each one builds on the last, and the
later stages assume the earlier ones work. Stage 1-3 are required; stage 4 is a
stretch goal if you have time left.

| Stage | Required? | What "done" looks like | Rough effort |
|---|---|---|---|
| 1. Clean `hubs-global.csv` | Required | IngestionServiceApp exposes the cleaned records via REST (see [Integration contracts](#integration-contracts)); every issue category in [ingestion-service/README.md](ingestion-service/README.md#known-data-issues) is handled | ~1-1.5h |
| 2. Wire up the REST services | Required | hub-service, delay-stage-service, and transit-service each expose real domain endpoints (not just `/health`) and call each other synchronously per the contracts below; `transit-service` can return an ETA for a hub | ~1.5-2h |
| 3. Decouple with the MQ topic | Required | `delay-stage-service` publishes to `package-status-topic` on stage change; `transit-service` subscribes instead of calling `delay-stage-service` directly; broker runs via `common/docker-compose.yml` | ~1h |
| 4. AlertBot | Stretch | `alertbot` subscribes to `package-status-topic` and simulates posting an alert when a hub's delay stage crosses a threshold you choose | ~30-45m |

You don't need to match any exact field names, endpoint paths, or message shapes —
the ones below are illustrative. Favor a working, readable implementation over a
gold-plated one; partial completion of stage 3 or 4 is fine if 1-2 are solid.

## Integration contracts

Two kinds of integration point exist in this repo: synchronous REST calls (stage 2)
and the asynchronous MQ topic (stage 3). Field/endpoint names below are illustrative
— reasonable variations are fine as long as the shape (who calls whom, with what
kind of payload) is preserved.

### REST (stage 2)

| Caller | Callee | Example | Purpose |
|---|---|---|---|
| hub-service | ingestion-service | `GET :7050/hubs` → JSON array of cleaned hub records | hub-service loads its place-name data from the cleaned CSV output instead of re-parsing it itself |
| transit-service | hub-service | `GET :7051/hubs/{hubId}` → hub/sorting-center details | transit-service needs hub location data to calculate an ETA |
| transit-service | delay-stage-service | `GET :7052/delay-stage/{hubId}` → `{ "hubId": "H-501", "stage": 3 }` | transit-service needs the current delay stage to calculate an ETA — **this call is replaced by the MQ subscription in stage 3** |
| (client) | delay-stage-service | `POST :7052/delay-stage/{hubId}` with a body like `{ "stage": 3 }` | the stage/state-change endpoint referenced in [common/README.md](common/README.md) — this is also where the stage-3 MQ publish happens |

### MQ (stage 3) — topic `package-status-topic`

Already documented in detail in [common/README.md](common/README.md): broker URL and
topic name come from the shared `co.wethinkcode.logisticsconnect.mq.MqConfig` class,
duplicated into each participating service.

- **Producer:** `delay-stage-service`, on its stage/state-change endpoint above.
- **Consumers:** `transit-service` (replacing its direct REST call to
  delay-stage-service) and, for the stretch goal, `alertbot`.
- **Example message shape:** `{ "hubId": "H-501", "stage": 5, "timestamp": "2026-07-18T10:15:00Z" }`

## Project structure

```
logisticsconnect/
├── README.md
├── .gitignore
├── ingestion-service/          (port 7050)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/
│       ├── java/co/wethinkcode/logisticsconnect/IngestionServiceApp.java
│       └── resources/hubs-global.csv
├── hub-service/          (port 7051)
├── delay-stage-service/          (port 7052)
├── transit-service/          (port 7053)
├── common/
│   ├── docker-compose.yml
│   └── README.md
└── alertbot/          (port 7054)
```

## Build

Requirements: Java 17+, Maven 3.8+, Docker (for the broker in `common/`).

Every folder here (`ingestion-service/`, each domain service, and `alertbot/`) is
an **independent** Maven project — there is no parent/aggregator pom. Build one at a
time, e.g.:

```
cd hub-service
mvn package
```

...or build every module in the repo in one pass from the project root:

```
find . -name pom.xml -execdir mvn -q package \;
```

## Run

```
# MQ broker (stages 3 and 4)
cd common && docker compose up -d

# ingestion
cd ingestion-service && mvn package && java -jar target/ingestion-service.jar

# domain services, each in its own terminal
# terminal 1
cd hub-service && mvn package && java -jar target/hub-service.jar
# terminal 2
cd delay-stage-service && mvn package && java -jar target/delay-stage-service.jar
# terminal 3
cd transit-service && mvn package && java -jar target/transit-service.jar

# alerting
cd alertbot && mvn package && java -jar target/alertbot.jar
```

| Service | Port |
|---|---|
| IngestionServiceApp (`ingestion-service`) | 7050 |
| HubServiceApp (`hub-service`) | 7051 |
| DelayStageServiceApp (`delay-stage-service`) | 7052 |
| TransitServiceApp (`transit-service`) | 7053 |
| AlertBotApp (`alertbot`) | 7054 |

## Test

Every module has JUnit tests that need nothing else running: run `mvn test` in a module's
folder. The messaging tests start an in-process ActiveMQ broker, so Docker isn't needed for
them.

| Module | Tests |
|---|---|
| `ingestion-service` | 51 |
| `hub-service` | 18 |
| `delay-stage-service` | 36 |
| `transit-service` | 53 |
| `alertbot` | 42 |

Each running service also exposes `/health` for a quick manual check:

```
curl http://localhost:7050/health   # -> OK
```

The end-to-end runs, with every service and the broker together, are recorded in
[IMPLEMENTATION.md](IMPLEMENTATION.md).
