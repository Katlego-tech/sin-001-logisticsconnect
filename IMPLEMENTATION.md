# LogisticsConnect: implementation notes

What was built, the decisions behind it, and what was checked. This file grows with each stage
from the [README](README.md#your-task).

| Stage | State |
|---|---|
| 1. Clean `hubs-global.csv` | done |
| 2. REST services | not started |
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
