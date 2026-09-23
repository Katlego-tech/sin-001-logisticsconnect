# IngestionServiceApp

## Overview

Parses and cleans `hubs-global.csv`, a messy legacy export of hubs, sorting centers, and regional districts data, and is the
first stop in the LogisticsConnect pipeline. Independent Maven module, no parent pom.

Part of the [LogisticsConnect](../README.md) project.

## Known data issues

`hubs-global.csv` is deliberately messy — cleaning it is the point of this service. Look
out for (and handle) at least:

- **Inconsistent casing** in IDs, names, and status/category values (`Active` /
  `active` / `ACTIVE`)
- **Padding** — leading/trailing spaces, and the occasional double space, inside
  fields
- **Duplicate records** for the same real-world entity, written with a different ID
  casing/format and/or slightly different field values
- **Inconsistent date formats** (`YYYY-MM-DD`, `MM/DD/YYYY`, `DD-MM-YYYY`, one- and
  two-digit months/days) and outright invalid dates
- **Missing / placeholder values** — blank fields, `N/A`, `n/a`, `TBD`, `unknown`,
  `-`, `NaN`
- **Invalid or non-numeric values** in numeric columns (negative counts, spelled-out
  numbers, unrealistic values)
- **Inconsistent boolean/flag representations** (`Y`/`N`, `yes`/`no`, `1`/`0`,
  `true`/`FALSE`)
- **Naming/spelling variants** for the same thing (e.g. regional spelling
  differences, synonyms)

## Worked example

Two raw rows from `hubs-global.csv`:

```
hub_id, Province ,sorting_center,active
H-502 ,gauteng,Pretoria North,0
H-505,Western Cape ,Cape Town  Port,TRUE
```

A reasonable cleaned shape for those same two rows:

| hub_id | province | sorting_center | active |
|---|---|---|---|
| H-502 | Gauteng | Pretoria North | false |
| H-505 | Western Cape | Cape Town Port | true |

That covers padding (`H-502 ` → `H-502`), casing (`gauteng` → `Gauteng`), a
collapsed double space (`Cape Town  Port` → `Cape Town Port`), and boolean
normalization (`0`/`TRUE` → `false`/`true`). Column names, exact casing
convention, and boolean representation are up to you — just be consistent.

This doesn't cover deduplication: rows like `H-500`, `H-504`, `H-510`, and `H-515`
all describe "Johannesburg Central" in "Gauteng" under different hub IDs, with
conflicting `active` values between them. How you resolve that (which one wins,
how you detect they're duplicates in the first place) is part of the exercise —
there's no single correct answer, but be ready to explain your reasoning.

## Project structure

```
ingestion-service/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/co/wethinkcode/logisticsconnect/
    │   │   ├── IngestionServiceApp.java   routes; cleans the bundled CSV at startup
    │   │   ├── HubCsvCleaner.java         row cleaning, then merging duplicates
    │   │   ├── Values.java                per-field rules (whitespace, placeholders, flags, casing)
    │   │   ├── Provinces.java             the nine provinces and their spellings
    │   │   ├── CleanHub.java              one cleaned hub
    │   │   └── CleaningReport.java        rows read, ignored columns, rejected rows, hubs
    │   └── resources/hubs-global.csv
    └── test/java/co/wethinkcode/logisticsconnect/
```

## Build

```
mvn package
```

## Run

```
java -jar target/ingestion-service.jar
```

Listens on port `7050`. The CSV is cleaned once, at startup.

| Endpoint | Returns |
|---|---|
| `GET /hubs` | the cleaned records: one per real-world hub, sorted by ID, each with its `aliases` (the other IDs the export used for it) and `notes` (how the record was derived) |
| `GET /report` | how they were derived: `rowsRead`, `ignoredColumns` (header columns the model doesn't use), `rejected` rows with their line in the file and the reason, and the `hubs` |
| `GET /health` | `OK` |

```
curl localhost:7050/hubs      # 10 hubs from 18 rows
curl localhost:7050/report
```

How each data issue above is handled, and why duplicates are merged the way they are, is in
[IMPLEMENTATION.md](../IMPLEMENTATION.md#stage-1-cleaning-hubs-globalcsv).

## Test

```
mvn test
```

Nothing else needs to be running. The suites cover each cleaning rule (`ValuesTest`), the
row-level and merge behaviour including rejected rows (`HubCsvCleanerTest`), the real bundled
file end to end (`BundledCsvTest`), and the endpoints over HTTP on a random port
(`IngestionServiceAppTest`). To check a running instance is up:

```
curl http://localhost:7050/health   # -> OK
```
