# LogisticsConnect: implementation notes

What was built, the decisions behind it, and what was checked. This file grows with each stage
from the [README](README.md#your-task).

| Stage | State |
|---|---|
| 1. Clean `hubs-global.csv` | done |
| 2. REST services | done |
| 3. `package-status-topic` | done |
| 4. AlertBot | not started |

## Stage 1: cleaning `hubs-global.csv`

```
cd ingestion-service && mvn package && java -jar target/ingestion-service.jar
curl localhost:7050/hubs      # 10 cleaned hubs from 18 rows
curl localhost:7050/report    # rows read, columns ignored, rows rejected and why
```

The export is bundled and can't change while the service runs, so it is cleaned once, at
startup. Cleaning happens in two passes: each row on its own, then merging the rows that
describe the same hub.

### Every issue category, and what happens to it

The README lists eight kinds of mess. Six are in this file; two have no column to occur in.

| Issue | In this file | What the cleaner does |
|---|---|---|
| Inconsistent casing | `h-501`, `gauteng`, `johannesburg central`, `TRUE` | IDs upper-cased; provinces mapped to their official names; names title-cased word by word; flags read in any casing |
| Padding, double spaces | `H-502 `, ` Gauteng `, `Cape Town  Port`, the header's ` Province ` | trimmed, and runs of whitespace collapsed to one space (header names too) |
| Duplicate records | Johannesburg Central ×4, Cape Town Port ×3, Durban Harbour ×3, Pretoria North ×2 | merged, see below |
| Placeholders / missing values | blank province (H-508), `unknown`, `N/A` | blank, `N/A`, `TBD`, `unknown`, `-`, `NaN`, `null`, `none` all become `null`, with a note on the record saying what the source said |
| Boolean variants | `Y yes YES 1 true TRUE` / `N no 0 FALSE` | `true` / `false`; anything else is `null`, never a guess |
| Spelling variants | `Kwa-Zulu Natal`, `KwaZulu Natal` | compared by letters only against the nine official names (and abbreviations like `KZN`), so both are `KwaZulu-Natal`; the change is noted on the record |
| Date formats, invalid dates | no date column | nothing to normalise, and inventing a date field would be making data up |
| Non-numeric numbers | no numeric column | the same; the one number there is, inside the hub ID, is checked: an ID not shaped `H-<number>` is kept but noted |

If a future export adds a column (a date, say), it is not silently dropped: `GET /report`
lists it under `ignoredColumns`, and the service logs a warning at startup.

### Duplicates: found by name, not by ID

- **The export gave Johannesburg Central four IDs** (H-500, H-504, H-510, H-515), so matching
  on ID finds nothing. Rows are matched on sorting-center name instead, compared by letters
  only, so `johannesburg central` and `Cape Town  Port` match their tidier twins.
- **Same name, different province: different hubs.** A "Central Depot" in Gauteng and one in
  Limpopo are not merged. A row with no province joins only if there is exactly one province it
  could mean, which is how H-508 (blank province) joins Pretoria North in Gauteng.
- **The lowest ID is kept**, numerically (H-9 before H-10), and the others become `aliases`.
  The result is the same whatever order the file is in.
- **Conflicting `active` flags: the majority wins, and a tie stays `null`.** The export has
  no timestamps, so "the latest row wins" has nothing to stand on. Johannesburg Central is 3
  to 1 for active. Pretoria North is 1 to 1, so it is unknown, and says so.
- **Every decision is visible.** A merged record's `notes` say which rows were merged, which ID
  was kept, and each row's vote when they disagreed:

  ```
  merged 4 rows for this sorting center (H-500, H-504, H-510, H-515); kept the lowest ID, H-500
  active disagrees across rows (H-500=true, H-504=true, H-510=false, H-515=true); majority says true
  ```

### Smaller decisions

- **A bad row never stops the file.** It is rejected with its line in the file and the reason
  (`GET /report`). A header missing a required column fails at startup instead, because that
  is the wrong file, not a bad row. A byte-order mark before the header, which spreadsheet
  exports often add, is ignored.
- **Casing is fixed without inventing spellings.** Names are title-cased word by word, but a
  word with a capital after a lower-case letter, like `McCarthy`, is kept as written: careless
  casing never produces that, an author does. The cost is that an all-caps acronym (`OR`)
  would become `Or`; there are none in this data.
- **Unknown is sent as `null`, not left out,** so a consumer can tell "unknown" from "not sent".
- **Not changed: `Port Elizabeth Hub`.** The city is now Gqeberha, but renaming a hub is a
  business decision, not cleaning.

### Checked

`mvn test` in `ingestion-service`: 51 tests covering each rule, the merges and rejections,
the real file end to end (18 rows, 10 hubs, nothing rejected), and both endpoints over HTTP.
The jar was also run and queried with `curl`; its output matched a by-hand reading of the 18
rows.

## Stage 2: the REST services

```
ingestion-service ◀── GET /hubs ── hub-service ◀── GET /hubs/{id} ── transit-service ──▶ GET /eta/{hubId}
     (7050)                          (7051)       ◀── GET /hubs/{id} ──┐      (7053)
                                                                       │        │ GET /delay-stage/{id}
                                     POST /delay-stage/{hubId} ──▶ delay-stage-service (7052)
```

Start the four services (each from its own folder, `java -jar target/<module>.jar`, in any
order), then:

```
curl localhost:7051/hubs/H-510                                  # an alias: answers with H-500
curl localhost:7051/provinces
curl -X POST localhost:7052/delay-stage/H-506 -d '{"stage": 5}'  # H-506 is Durban: stored as H-503
curl localhost:7053/eta/H-503                                   # DELAYED, stage 5, 84-168h
```

| Service | Endpoint | Notes |
|---|---|---|
| hub-service | `GET /hubs`, `GET /hubs/{hubId}`, `GET /provinces` | by ID or alias; 404 unknown; 503 if ingestion-service is down |
| delay-stage-service | `POST /delay-stage/{hubId}`, `GET /delay-stage/{hubId}`, `GET /delay-stage` | 400 bad stage; 404 unknown hub; 503 if hub-service is down |
| transit-service | `GET /eta/{hubId}` | 404 unknown hub; 503 naming whichever dependency is down |

| Variable | Used by | Default |
|---|---|---|
| `INGESTION_URL` | hub-service | `http://localhost:7050` |
| `HUB_SERVICE_URL` | delay-stage-service, transit-service | `http://localhost:7051` |
| `DELAY_STAGE_URL` | transit-service | `http://localhost:7052` |

### Decisions

- **hub-service is the one place that knows about aliases.** `GET /hubs/H-504` answers with
  H-500's record, so no other service needs the duplicate list. It loads its data from
  ingestion-service on first use and keeps it; if ingestion-service is down, that request is a
  503 and the next one tries again, so the services can start in any order.
- **"No such hub" and "couldn't ask" are never confused.** 404 means the hub doesn't exist. If
  a dependency can't be reached, answers with an error, or sends something unreadable, the
  answer is a 503 whose message names that service. A 500 upstream is not passed through as
  this service's own 500.
- **A stage belongs to a real hub, so delay-stage-service checks with hub-service on reads as
  well as writes.** An alias is stored and read under its canonical ID. Checking only on writes
  would answer `GET /delay-stage/anything` with a confident 200 "stage 0", and stage 0 for an
  alias whose hub is at stage 5. That is making data up.
- **The body is validated before anyone is asked anything.** `{"stage": n}` with a whole number
  from 0 to 8, or a 400 that says so. Setting the stage a hub is already at is answered with
  `"changed": false`.
- **Replies are read tolerantly but not credulously.** Each service keeps its own small copy of
  the records it reads and ignores fields it doesn't use, so a producer can add fields without
  breaking anyone. But a value it does use must be valid: a missing or malformed stage from
  delay-stage-service is an error, not stage 0, which would report a hub as on time because of
  a reply transit-service didn't understand.
- **If two different hubs ever claim one ID** (not in this data; ingestion flags it in the
  records' notes), the first keeps it for lookups and hub-service logs a warning.

### The ETA model

The legacy data has no transit times, so these are assumptions, all kept in `EtaCalculator`:

- Metro provinces (Gauteng, Western Cape, KwaZulu-Natal): 24–48h. Everywhere else: 48–96h.
- Each delay stage adds 12h to the earliest arrival and 24h to the latest, so a delay makes the
  window both later and less certain.
- Stage 8 is a shutdown (`SUSPENDED`, no window). An inactive hub is `HUB_INACTIVE`.
- Every assumption behind an estimate is listed in `warnings`: an active flag the source didn't
  give, an unknown province (the regional window is used), or a request made by alias.

### What synchronous REST costs here

Every ETA is three calls deep: transit asks hub-service, then asks delay-stage-service, which
asks hub-service again. If any of them is down, the ETA is a 503, even though the stage rarely
changes. The run below shows it: with delay-stage-service stopped, transit-service can't answer
at all. Stage 3 removes that dependency.

### Checked

`mvn test`: 18 tests in hub-service, 25 in delay-stage-service, 29 in transit-service. The HTTP
clients are tested against stub HTTP servers: status codes, unreadable and malformed replies,
nothing listening.

The four jars were also run together:

| Scenario | Result |
|---|---|
| hub-service started before ingestion-service | 503 naming ingestion-service; 200 once it was up, no restart |
| `POST /delay-stage/H-506 {"stage": 5}` (an alias) | stored as H-503; `GET /delay-stage/H-506` answered for H-503 |
| `GET /eta/H-503` at stage 5 | `DELAYED`, 84–168h |
| `GET /eta/h-515` with H-500 at stage 8 | answered for H-500: `SUSPENDED`, no window, alias warning |
| `GET /eta/H-507`, `GET /eta/H-502` | `HUB_INACTIVE`; `ON_TIME` with "doesn't say whether this hub is active" |
| stage 9; unknown hub; `GET /delay-stage/H-999` | 400; 404; 404 |
| the same stage posted twice | 200, `"changed": false` |
| delay-stage-service stopped | `GET /eta/H-503` → 503 naming delay-stage-service |
| hub-service stopped | ETA, stage change and stage read all 503 naming hub-service |

## Stage 3: `package-status-topic`

```
POST /delay-stage/{hubId} ──▶ delay-stage-service ──publish──▶ package-status-topic ──durable──▶ transit-service
                              (checks the hub with hub-service)                                (stage view, saved to data/)
```

transit-service no longer calls delay-stage-service. It answers each ETA from its own view of
every hub's stage, kept up to date by the topic.

```
cd common && docker compose up -d            # the broker (console: http://localhost:8161)
```

| Variable | Used by | Default |
|---|---|---|
| `STAGE_SOURCE` | transit-service: `mq` (the topic) or `rest` (stage 2's call per ETA) | `mq` |
| `STAGE_VIEW_FILE` | transit-service: where its stage view is saved | `data/stage-view.json` |

### Decisions

- **Publish, then record.** delay-stage-service changes a stage only after the broker confirms
  the event, so no subscriber ever misses a change this service claims happened. The change
  stays synchronized through the publish, so two changes can't be published in one order and
  recorded in the other.
- **A timeout is reported as a timeout.** If the broker can't be reached, nothing was sent: 503
  "stage not changed". But if a send isn't confirmed in time, the broker may still deliver it
  once it recovers (the live run below saw exactly that), so the answer is a 503 "stage not
  recorded … subscribers may still receive it; send the same change again". Repeating the
  change brings this service and its subscribers back into agreement.
- **Writes still check the hub with hub-service, synchronously, on purpose.** A stage has to
  belong to a real hub, and resolving aliases here means every event carries the canonical
  ID. Writes are rare. The per-request read in transit-service is the call worth decoupling,
  and this stage removes it.
- **The event carries the hub's name, province and previous stage**, not just its ID and
  stage. A subscriber can then describe the hub, or notice a threshold being crossed, without
  calling anyone or remembering anything.
- **transit-service subscribes durably, and saves what it has built.** A durable subscription
  replays only the events a subscriber *missed*; the ones it already consumed would be lost on
  a restart if they lived only in memory. So the view is written to `data/stage-view.json`
  before each message is acknowledged (to a temp file, then renamed, so a crash never leaves
  half a file) and loaded at startup. A crash between the write and the acknowledgement just
  causes a redelivery, which is harmless: an event older than the one already applied is
  ignored.
- **Events are read tolerantly, but not credulously.** Fields transit-service doesn't use are
  ignored, so the publisher can add some. But a missing or null stage, a stage outside 0–8, a
  blank hub ID or a timestamp that isn't an instant makes an event unreadable. It is logged
  and dropped rather than turned into stage 0 (a false "on time") or a nonsense window.
- **Why the connections differ.** The subscriber connects with `failover:` on a background
  thread, so transit-service starts and answers even with the broker down, and survives a
  broker restart without code of ours. The publisher uses plain `tcp://`, because `failover:`
  would hold the caller's HTTP request until the broker came back; the caller should get a
  prompt 503. Every wait the publisher makes is bounded to 3 seconds: the TCP connect (30s by
  default), the OpenWire handshake (15s by default), the broker's reply and the send. A broken
  connection is closed in the background (closing one to a hung broker waits 15s by default),
  and a JMS `ExceptionListener` drops the connection as soon as the client notices the broker
  has gone, so the next change reconnects instead of failing on a dead session.
- **The HTTP port is bound before the subscriber starts.** If the port is taken, startup fails
  before a background subscriber exists that would keep a half-started process alive.
- **The stage-2 path is kept behind the same interface.** `STAGE_SOURCE=rest` puts back the
  REST call, so the swap is visible in one place (`StageSource`). Any other value stops startup
  rather than quietly picking one.
- **The event's timestamp orders events, not a sequence number.** delay-stage-service keeps its
  stages in memory and restarts empty (see below), so a sequence would restart at 1 and every
  new event would look older than the saved view.

### Known limits

- **delay-stage-service restarts empty.** Its stages are in memory, so after a restart it
  reports every hub at stage 0 while transit-service's saved view still has the last stages it
  heard, until each hub next changes. The fix is a database, and then a transactional outbox,
  because "publish, then record" only works while recording can't fail.
- **transit-service's very first start knows nothing.** A durable subscription exists only
  from the first time it is made, so changes before then are never replayed; ETAs for those
  hubs report the stage as assumed (`"stageKnown": false`, with a warning). Seeding the view
  from `GET /delay-stage` at startup would fill the gap, at the price of bringing back a call to
  delay-stage-service, which this stage exists to remove.
- **Retrying an unconfirmed change can repeat an event.** Subscribers apply the stage again,
  which is harmless for transit-service's view.
- **One transit-service at a time.** The durable subscription is tied to a client ID; scaling
  out would need ActiveMQ virtual topics (a queue per consumer group).

### Checked

`mvn test`: 36 tests in delay-stage-service, 53 in transit-service. The MQ tests run against a
real in-process ActiveMQ broker, so they need no Docker. Among them: the exact JSON arrives on
the topic as a persistent message; an event published while transit-service was disconnected
is delivered when it reconnects; invalid events are skipped; the view survives a restart. A
TCP proxy that can freeze stands in for a hung broker: the publish fails within about 3
seconds, and once the proxy thaws, the "failed" event arrives.

### Verified end-to-end

The four services were run from their own folders against the broker from
[`common/docker-compose.yml`](common/docker-compose.yml) (`apache/activemq-classic:5.18.3`),
from a fresh broker, with the broker's own statistics (read through its Jolokia API) as
evidence alongside the logs:

| Scenario | Result |
|---|---|
| transit-service's first start | subscribed durably; the broker showed 1 active durable subscriber |
| `POST /delay-stage/H-504 {"stage": 5}` (an alias) | stored and published as H-500; topic enqueued 1, dequeued 1; transit-service logged the update, and `GET /eta/H-500` answered `DELAYED`, stage 5, 84–168h, from its own view |
| delay-stage-service stopped | the ETA still answered, stage 5. With `STAGE_SOURCE=rest`, the same request was a 503 naming delay-stage-service |
| transit-service restarted with no new events | reloaded the stage from `data/stage-view.json`; the ETA was unchanged |
| transit-service down while Durban went to stage 6 (by alias H-506) | the broker held the event for the inactive durable subscriber (enqueued 2, dequeued 1); on restart transit-service caught up: H-503 at stage 6 |
| broker hung (`docker pause`) | the POST was a 503 "stage not recorded … subscribers may still receive it" in 3.0s; delay-stage-service kept stage 6; ETAs kept answering. After `docker unpause` the event *was* delivered, and repeating the change left both at 7 |
| broker stopped | the POST was a 503 "stage not changed" in under 10ms; ETAs kept answering from the view |
| broker back | the next POST succeeded; transit-service's `failover:` connection reconnected on its own and received it; the durable subscription survived the broker restart |
| broker restarted with no change during the outage | the next POST succeeded first time |
| a second transit-service started while the port was in use | exited with code 1, having made no subscription |
| `STAGE_SOURCE=rset` | exited with code 1 before opening a port: "STAGE_SOURCE must be 'rest' or 'mq', not 'rset'" |
| transit-service started while the broker was down | answered ETAs at once from its saved view, then subscribed when the broker returned and received the next change |

The run also showed the first known limit above in action: after delay-stage-service was
restarted, it reported H-500 at stage 0 while transit-service's view still had stage 5, until
the next change.

**Found by this run, and fixed** (each now has a test):

- With the broker hung, a POST took **18 seconds** to fail: the send timed out after 3s, then
  closing the dead connection waited 15s. Now about 3 seconds.
- After a broker restart with no change in between, the first POST failed with "The Session is
  closed", although the broker was up: the dead connection was only noticed when a send failed
  on it. Now it is dropped as soon as the connection is lost.
- A POST answered "stage not changed" could still reach the subscribers: the timed-out send
  was delivered when the broker recovered, leaving transit-service at stage 7 and
  delay-stage-service at 6, with the caller told nothing had changed. Now that case says the
  outcome is unknown and how to fix it.
