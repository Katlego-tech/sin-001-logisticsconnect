# LogisticsConnect: implementation notes

What was built, the decisions behind it, and what was checked. This file grows with each stage
from the [README](README.md#your-task).

| Stage | State |
|---|---|
| 1. Clean `hubs-global.csv` | done |
| 2. REST services | done |
| 3. `package-status-topic` | not started |
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
