# AlertBotApp

## Overview

Posts proactive delay notifications to public transit social media pages (simulated).

Part of the [LogisticsConnect](../README.md) project — its alerting service.
Independent Maven module, no parent pom.

Mechanism: Outbound webhook, simulated social post

It subscribes (non-durably) to the ActiveMQ topic `package-status-topic` — see
[`../common/`](../common). Broker URL and topic name come from the common
`co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module. When a hub
crosses `ALERT_THRESHOLD` (default 4), gets worse while over it, reaches stage 8, leaves it,
or drops back under, it posts a simulated alert; anything else is logged as not news.
[IMPLEMENTATION.md](../IMPLEMENTATION.md#stage-4-alertbot) has the full table and the
reasoning.

| Variable | Meaning | Default |
|---|---|---|
| `ALERT_THRESHOLD` | delay stage (1–8) worth a public post; anything else stops startup | `4` |
| `ALERTBOT_WEBHOOK_URL` | also send each post there as `{"text": …}` | unset: logged only |

## Project structure

```
alertbot/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── AlertBotApp.java       GET /health, GET /posts; binds the port, then subscribes
    │   ├── StageSubscriber.java   non-durable subscription to package-status-topic
    │   ├── StageChanged.java      the event, validated as it is read
    │   ├── AlertPolicy.java       which changes are worth a post, and its wording
    │   ├── SocialFeed.java        the simulated page: newest 50 posts, optional webhook
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
java -jar target/alertbot.jar
```

Listens on port `7054`. What would have been posted, newest first:

```
curl http://localhost:7054/posts   # -> {"threshold":4,"posts":[{"hubId","stage","text","postedAt","delivery"}, …]}
```

## Test

Run this module's tests with `mvn test` (nothing else needs to be running: the MQ tests use an
in-process ActiveMQ broker). To check a running instance is up:

```
curl http://localhost:7054/health   # -> OK
```
