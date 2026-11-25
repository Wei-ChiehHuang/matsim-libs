# Plan: Segmented PT skims for Munich–Bavaria

Status: implemented (`PTSkimMatricesSegmented`, `ScheduleCheckUtils`, `CalculateSegmentedSkimsMuc`).
Date: 2026-11-13

## 1. Goal

Calculate three PT skims and one walk skim for the morning peak (07:30–08:30). In each PT skim the router may use all
allowed modes and transfer freely between them. Afterwards, the legs of every connection are classified into
*walk access → PT access (feeder) → PT main → PT egress (feeder) → walk egress*.

| Skim | Prefix | Schedule + network (one combined file each) | Main mode M | Feeder modes |
|---|---|---|---|---|
| 1 | `bus_` | Bus | bus, ferry | none |
| 2 | `btm_` | Bus + tram/metro | tram, subway | bus, ferry |
| 3 | `all_` | Bus + tram/metro + rail | rail | bus, ferry, tram, subway |
| walk | `walk_` | none | – | – |

Decisions taken:

- **Approach A.** The router finds the best connection over all allowed modes, and its legs are classified afterwards.
  Trips are **not** forced onto the main mode.
- **Trips without a main-mode leg** do not count as valid results for that skim (see 4.3).
- **Averaging** over point pairs and the time window uses only main-mode connections (option i).
- **Main-mode share** is calculated by segment: `pt_main / (pt_access + pt_main + pt_egress)`.
- **No connection:** `Infinity` in all matrices, and frequency = 0.
- **Short OD pairs** that are dominated by walk-only routes (see 7.1) are accepted. The walk skim covers them.
- **Adaption time and frequency** are calculated over **all** PT connections (overall service level).
- `PTSkimMatrices` stays untouched. A new class, `PTSkimMatricesSegmented`, is added.

## 2. Code structure

| Part | Where | What |
|---|---|---|
| Segment-aware skim calculator | **new** `ch.sbb.matsim.analysis.skims.PTSkimMatricesSegmented` | A copy of `PTSkimMatrices` with the leg classification and the new matrices. The original class stays untouched. |
| Schedule checks | **new** `ch.sbb.matsim.analysis.skims.ScheduleCheckUtils` | Checks each skim's schedule against its network (no merging: the user provides one combined schedule and network per skim). |
| Driver | **new** `CalculateSegmentedSkimsMuc.java` | Sets the config, creates or loads the sampling points, runs the 3 PT skims and the walk skim, and writes the outputs. Inputs are placeholders. A separate class, so the untracked `CalculateSkimMatricesMuc.java` stays as it is. |
| Test | **new** `src/test/.../PTSkimMatricesSegmentedTest.java` | Synthetic bus → rail → bus corridor (section 9). |
| Reused unchanged | `RooftopUtils`, `FloatMatrix`, `FloatMatrixIO`, `BeelineDistanceMatrix`, sampling-point logic | |

## 3. Inputs and settings

### 3.1 Sampling points
- 3 points per zone, drawn once from the car network nodes (as now in `CalculateSkimMatricesMuc`, seed 4711).
- They are written to `zone_coordinates.csv`. **All runs load this file**, so the skims are directly comparable.

### 3.2 Config
There is no config file. The config is created in code (`ConfigUtils.createConfig()`, `CalculateSkimMatricesMuc.java:183`),
so MATSim defaults apply unless they are set explicitly:

| Parameter | Config group | Default | Planned value |
|---|---|---|---|
| `maxBeelineWalkConnectionDistance` | `transitRouter` | 100 m | **500 m** |
| `searchRadius` | `transitRouter` | 1000 m | default |
| `extensionRadius` | `transitRouter` | 200 m | default |
| `additionalTransferTime` (minimum transfer time) | `transitRouter` | 0 s | default |
| `non_network_walk` `teleportedModeSpeed` | `routing` | 3 km/h (0.833 m/s), `RoutingConfigGroup.java:328` | **default (decided)** |
| `non_network_walk` `beelineDistanceFactor` | `routing` | 1.3 (global default), `RoutingConfigGroup.java:72` | default |

The router reads the walk parameters of the first teleported mode found in the order `transit_walk` →
`non_network_walk` → `walk` (`RaptorUtils.java:67-73`, `TransitRouterConfig.java:125-139`). With a default config
`transit_walk` is not defined, so **`non_network_walk`** is used, not `walk`. To change the speed, set it on
`non_network_walk` (or add `transit_walk` params).

The router uses `beelineWalkSpeed = speed / beelineDistanceFactor` (`RaptorUtils.java:82`), so a walk time is
`beeline distance × 1.3 / 0.833 m/s`. The walk skim uses the same two values.

### 3.3 Time window: why 07:30–08:30, but routing trees from 06:30 to 09:30 every 120 s

The **evaluation window** is 07:30–08:30. All averages, shares, adaption times and frequencies describe a traveller
who wants to leave at some moment within this hour. The window must be **exactly 3600 s**, because `RooftopUtils`
divides by a hard-coded `3600` (`RooftopUtils.java:141, 144, 187, 212, 224`). Any other length would give wrong
connection shares and frequencies.

The **routing trees** are computed over a wider range: from `start − window` to `end + window`
(`PTSkimMatrices.java:207-209`), i.e. 06:30–09:30. The reason is how the rooftop algorithm works:

- **The traveller may adapt in both directions.** For each wished departure time in the window, the algorithm looks
  for the connection that minimises *adaption time + travel time*. That connection may leave before 07:30 or after
  08:30. Example: someone who wants to leave at 07:32 may be best served by a train at 07:25, so departures before
  07:30 must be known.
- **The end of the window needs the next departure after it.** For a wished departure at 08:25, the best connection
  may be the one at 08:40. Without it, the end of the hour would look as if there were no service at all.
- **Low-frequency lines.** With a 60-minute headway, the neighbouring departures can be up to one hour before or after
  the window. Extending by one full window length (±60 min) catches them. Lines with headways above 60 minutes may
  still be underestimated at the edges.
- **Long access times.** The connection's departure time is shifted by its access walk (`departureTime − accessTime`).
  A train at 07:40 with 15 min access counts as a door-to-door departure at 07:25, which again lies outside the window.

The **120 s step** (hard-coded, `CalculateSkimMatrices.java:522`) is the sampling of the search. A tree computed at time
*t* returns, for each stop, the best connection departing *at or after t*. Starting a tree every 2 minutes finds
practically every distinct departure. Only two departures less than 2 minutes apart may collapse into one, which
causes a small error in frequency. A smaller step is more accurate but costs proportionally more run time:
3 h / 120 s = 90 trees per origin point.

## 4. Calculation (inside `PTSkimMatricesSegmented`)

### 4.1 Per connection
For each connection the router finds (taken from the trees, as in `PTSkimMatrices`):

1. Take the PT legs of `RaptorRoute.getParts()` in order. Parts with `line == null` are transfer walks.
2. Find the first PT leg *f* and the last PT leg *l* whose mode is in M.
3. **If no leg is in M**, mark the connection as *non-main*. It does not contribute to the segment matrices.
4. **Otherwise, classify:**
   - `pt_access`: in-vehicle time and distance of PT legs **before** *f*
   - `pt_main`: in-vehicle time and distance of PT legs from *f* to *l*, **including** feeder legs in between
     (rail → bus → rail counts entirely as main)
   - `pt_egress`: in-vehicle time and distance of PT legs **after** *l*
   - in-vehicle time of a leg = `arrivalTime − boardingTime`; distance = `part.distance`
5. **Transfers**, wherever they occur (also between feeder and main):
   - transfer walk time and distance: sum over parts with `line == null` between the first and last PT leg
   - transfer wait: `boardingTime − depTime` for every PT leg except the first
   - the first wait at the first stop is **not** a transfer wait; it is represented by the adaption time
6. **Walk access and egress:**
   - time = beeline distance / `beelineWalkSpeed` (as now)
   - distance = beeline × `beelineDistanceFactor`
   - implementation detail: a walk inside the router result *before* the first PT leg (from the start stop to another stop)
     is added to walk access, and a walk *after* the last PT leg (to the final stop) to walk egress. That way the sum of
     the parts always equals the door-to-door time.
7. **Stay-seated (chained) departures** count as one leg: in-vehicle time from the first boarding to the last arrival.
   The leg counts as main if any of its parts is a main mode.

### 4.2 Per point pair (rooftop)
1. Connection filtering, connection shares and average adaption time are calculated with `RooftopUtils` over
   **all** connections (unchanged). Adaption time and frequency therefore describe the overall PT service.
2. For the segment matrices, the shares of main-mode connections are **renormalised to sum to 1**. The
   segment values are averaged with these shares.
3. `main availability (point pair)` = sum of the original shares of main-mode connections (0–1).

### 4.3 Per zone pair
1. The point pairs are averaged, using only point pairs that have at least one main-mode connection.
2. `main_mode_availability` = average of the point-pair availabilities over **all** point pairs (0–1).
3. If availability is 0 (no main-mode connection at all), **all** matrices are `Infinity`, except frequency (0)
   and availability (0).

## 5. Outputs

Each skim writes the files below with its prefix to the output directory, as `.csv.gz`, `FROM;TO;VALUE`.
Times are in seconds, distances in metres.

| File (after prefix) | Meaning | Unit |
|---|---|---|
| `walk_access_time` / `walk_access_dist` | zone point → first PT stop, walking | s / m |
| `walk_egress_time` / `walk_egress_dist` | last PT stop → zone point, walking | s / m |
| `pt_access_ivt` / `pt_access_dist` | feeder in-vehicle time and distance before the first main-mode leg (always 0 in skim 1) | s / m |
| `pt_main_ivt` / `pt_main_dist` | in-vehicle time and distance from the first to the last main-mode leg | s / m |
| `pt_egress_ivt` / `pt_egress_dist` | feeder in-vehicle time and distance after the last main-mode leg (always 0 in skim 1) | s / m |
| `pt_transfer_walk_time` / `pt_transfer_walk_dist` | all walks between PT legs | s / m |
| `pt_transfer_wait_time` | waiting at transfers (excludes the first wait) | s |
| `pt_transfer_count` | number of transfers | – |
| `pt_ivt_total` / `pt_ivd_total` | `pt_access + pt_main + pt_egress` (in-vehicle) | s / m |
| `total_travel_time` | `walk_access + pt_ivt_total + transfer_walk + transfer_wait + walk_egress` | s |
| `total_distance` | `walk_access + pt_ivd_total + transfer_walk + walk_egress` | m |
| `main_share_time` / `main_share_dist` | `pt_main / pt_*_total` (by segment); exactly 1 when there is no feeder | 0–1 |
| `main_mode_availability` | fraction of point pairs × wished departure times whose best connection uses M | 0–1 |
| `pt_adaptiontime` | average adaption time, over all PT connections | s |
| `pt_frequency` | `3600 / adaption time / 4`, over all PT connections | per hour |

Interpretation:

- `main_share = 1`: only main mode, no feeder in-vehicle time.
- `main_share` between 0 and 1: feeder legs exist.
- `main_mode_availability < 1`: in part of the hour or for some point pairs, the best route does not use the main
  mode. These are excluded from all values except adaption time and frequency.
- All values `Infinity`: no main-mode connection between the zones. Use the walk skim, or the skim of a lower level
  (e.g. `bus_`).

Shared outputs (no prefix):

| File | Meaning | Unit |
|---|---|---|
| `zone_coordinates.csv` | sampling points `ZONE;POINT_INDEX;X;Y` | coordinates in your input CRS (GK4, EPSG:31468) |
| `beeline_distances.csv.gz` | straight-line distance between zones, averaged over point pairs | m |
| `walk_time.csv.gz` | `beeline × beelineDistanceFactor / walkSpeed` | s |
| `walk_dist.csv.gz` | `beeline × beelineDistanceFactor` | m |
| `schedule_check_<prefix>.csv` | results of the schedule and network check (section 6) | – |

Consistency is built in: `total_travel_time` and `total_distance` are exactly the sums of their parts.

## 6. Schedules, networks and checks

> **Decided (2026-11-16):** the user provides **one combined schedule and one network per skim** (bus | bus +
> tram/metro | bus + tram/metro + rail). No merging of schedules or networks is done in code.

Steps per skim:

1. **Read** the skim's schedule and network. Stops not served by any route are ignored by the router automatically
   (`SwissRailRaptorData` only indexes stops that have route stops).
2. **Checks** (`ScheduleCheckUtils`), written to the log and to `schedule_check_<prefix>.csv`:
   - every stop's link reference and every link in each route's path exists in the network (missing links are ERROR;
     in-vehicle distances depend on them)
   - routes with a mode not in {`bus`, `ferry`, `tram`, `subway`, `rail`} (WARN)
3. The run **stops** on errors unless the `warnOnlyOnMissingLinks` flag is set.

Transfers between modes depend on the stops of the combined schedule lying within `maxBeelineWalkConnectionDistance`
(500 m) of each other, or on shared stop facilities.

## 7. Known limitations

### 7.1 Short OD pairs (accepted)
All stops within `searchRadius` (1000 m) of the origin point start with zero cost, and the router tree keeps only
the cheapest way to each stop. Destination stops inside that radius are therefore reached "walk-only" and are dropped.

- Most such pairs (same zone, neighbouring zones) become `Infinity`.
- Some get slightly too high values, because the route uses stops further away.

The walk skim covers these pairs.

### 7.2 Other
- The routing tree optimises cost (from the config) per stop. The rooftop algorithm then compares the connections
  that were found on door-to-door time. This is unchanged from the current behaviour.
- Lines with a headway above 60 min may be underestimated at the edges of the window (see 3.3).
- Access and egress use straight-line distance × detour factor, not a walking network.

## 8. Open points

1. **Networks:** the user prepares one network per skim later (see section 6).
2. **Walk speed:** decided: keep the default 3 km/h (`non_network_walk`) with detour factor 1.3.

No open points remain for the implementation.

## 9. Verification

### Unit test (`src/test/java`, small synthetic schedule)
Cases to cover:

| Case | Expected result |
|---|---|
| bus only | values in skim 1; `Infinity` in skims 2 and 3 |
| bus → rail → bus | access, main and egress all filled |
| rail → bus → rail | main share 1, `pt_access = pt_egress = 0` |
| feeder ↔ main transfer | walk and wait land in the transfer items |
| unreachable pair | `Infinity` everywhere, frequency 0 |
| one point pair bus-only, others main | renormalised average, availability < 1 |

### Sanity run on the Munich data
- Parts sum to the totals.
- Shares and availability lie in [0, 1].
- `all_` total time ≤ `btm_` total time ≤ `bus_` total time for most pairs, wherever all three are finite.
- 3–5 known OD pairs are checked by hand (e.g. against the MVV journey planner).
