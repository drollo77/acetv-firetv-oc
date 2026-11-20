# Graph Report - acetv-firetv-oc  (2026-11-17)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 337 nodes · 700 edges · 19 communities (10 shown, 6 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 25 edges (avg confidence: 0.83)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `aef0336c`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 58 edges
2. `Channel` - 28 edges
3. `QrCode` - 27 edges
4. `RemoteApiTest` - 18 edges
5. `RemoteApi` - 17 edges
6. `FakeController` - 15 edges
7. `QrSegment` - 15 edges
8. `BitBuffer` - 14 edges
9. `HttpRequest` - 14 edges
10. `HttpResponse` - 14 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --calls--> `RemoteApi`  [INFERRED]
  app/src/main/java/local/acetv/MainActivity.kt → app/src/main/java/local/acetv/RemoteApi.kt
- `MainActivity` --calls--> `RemoteSession`  [INFERRED]
  app/src/main/java/local/acetv/MainActivity.kt → app/src/main/java/local/acetv/RemoteSession.kt
- `MainActivity` --calls--> `StallDetector`  [INFERRED]
  app/src/main/java/local/acetv/MainActivity.kt → app/src/main/java/local/acetv/StallDetector.kt
- `RemoteApiTest` --calls--> `RemoteApi`  [INFERRED]
  app/src/test/java/local/acetv/RemoteTest.kt → app/src/main/java/local/acetv/RemoteApi.kt
- `RemoteApiTest` --calls--> `RemoteSession`  [INFERRED]
  app/src/test/java/local/acetv/RemoteTest.kt → app/src/main/java/local/acetv/RemoteSession.kt

## Import Cycles
- None detected.

## Communities (19 total, 6 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.08
Nodes (19): Activity, AdapterView, MainActivity, OnItemSelectedListener, Listener, Rows, ArrayAdapter, Bundle (+11 more)

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (6): Channel, ChannelLogic, ListParsers, FakeController, ListJsonTest, SearchAndEngineTest

### Community 2 - "Community 2"
Cohesion: 0.14
Nodes (9): BitBuffer, Mode, ALPHANUMERIC, BYTE, ECI, KANJI, NUMERIC, QrSegment (+1 more)

### Community 3 - "Community 3"
Cohesion: 0.15
Nodes (6): Ecc, HIGH, LOW, MEDIUM, QUARTILE, QrCode

### Community 4 - "Community 4"
Cohesion: 0.19
Nodes (4): HttpRequest, HttpResponse, RemoteApi, RemoteController

### Community 5 - "Community 5"
Cohesion: 0.16
Nodes (15): HttpError, Exception, MiniHttpServer, bodyOf(), cuerpoDemasiadoGrande(), echo(), getConParametrosCodificados(), open() (+7 more)

### Community 6 - "Community 6"
Cohesion: 0.10
Nodes (8): RemoteSession, Result, BAD, EXPIRED, LOCKED, OK, QrAndPanelTest, RemoteSessionTest

### Community 7 - "Community 7"
Cohesion: 0.28
Nodes (4): JsonError, Exception, MiniJson, Parser

### Community 10 - "Community 10"
Cohesion: 0.28
Nodes (3): EngineCheck, JsonOut, Search

### Community 12 - "Community 12"
Cohesion: 0.40
Nodes (3): QrRender, BooleanArray, IntArray

## Knowledge Gaps
- **13 isolated node(s):** `ALPHANUMERIC`, `BYTE`, `ECI`, `KANJI`, `NUMERIC` (+8 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 78 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **6 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `Community 0` to `Community 1`, `Community 4`, `Community 5`, `Community 6`, `Community 13`?**
  _High betweenness centrality (0.291) - this node is a cross-community bridge._
- **Why does `Channel` connect `Community 1` to `Community 0`, `Community 4`, `Community 9`, `Community 10`, `Community 11`?**
  _High betweenness centrality (0.240) - this node is a cross-community bridge._
- **Why does `RemoteSession` connect `Community 6` to `Community 0`, `Community 8`?**
  _High betweenness centrality (0.101) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `MainActivity` (e.g. with `RemoteApi` and `RemoteSession`) actually correct?**
  _`MainActivity` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 3 inferred relationships involving `Channel` (e.g. with `.ch()` and `.dedupeQuitaRepetidosEInvalidos()`) actually correct?**
  _`Channel` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `RemoteApiTest` (e.g. with `RemoteApi` and `RemoteSession`) actually correct?**
  _`RemoteApiTest` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `RemoteApi` (e.g. with `MainActivity` and `RemoteApiTest`) actually correct?**
  _`RemoteApi` has 2 INFERRED edges - model-reasoned connections that need verification._