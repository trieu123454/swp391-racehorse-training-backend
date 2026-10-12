# Head Trainer API

All endpoints require a signed-in `HEAD_TRAINER` account. Requests and responses use
snake_case. Write operations use one database transaction and create an `audit_logs`
record. Metric history excludes `is_simulated=true` rows unless
`include_simulated=true` is passed.

## Performance and training

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/head-trainer/overview` | Latest club-wide metrics, prior-value trends, lock and alert priority |
| GET | `/api/horses/{horseId}/training-metrics?from=&to=&include_simulated=` | One horse's time series |
| GET | `/api/head-trainer/training-metrics/compare?horse_ids=id1,id2&from=&to=` | Compare 2–8 horses |
| POST | `/api/training-schedules/{id}/metrics` | Save a measurement, evaluate heart-rate, speed, and stamina thresholds, and notify Veterinarians on alerts |
| POST | `/api/horses/{horseId}/training-plans` | Create a training plan |
| GET | `/api/horses/{horseId}/training-plans?active_only=` | List plans and attached session counts |
| PATCH | `/api/training-plans/{id}` | Update allowed fields and record field-only history |
| POST | `/api/horses/{horseId}/training-schedules` | Create a calendar event and training session |
| GET | `/api/head-trainer/calendar?from=&to=&horse_id=&groom_id=` | List sessions by date, horse, or Groom |
| GET | `/api/head-trainer/grooms` | List approved Groom accounts for assignment |
| PATCH | `/api/training-schedules/{id}/assign-groom` | Assign an active Groom and notify them |
| GET/PATCH | `/api/training-schedules/{id}` | Read session details and metrics, or update status/notes |
| GET | `/api/head-trainer/races?from=&to=` | List upcoming races for registration |
| POST | `/api/horses/{horseId}/race-entries` | Register a horse in a real race |
| GET | `/api/horses/{horseId}/race-entries` | List a horse's race entries |

Training lock checks and calendar collision checks use the same services as the
veterinarian module. Groom IDs are numeric `users.user_id` values because the
existing database preserves its BIGINT user IDs.

## Race simulation

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/head-trainer/race-simulations` | Create a seeded demo session and return horse parameters |
| GET | `/api/head-trainer/race-simulations/{id}` | Read saved session parameters and results |
| POST | `/api/head-trainer/race-simulations/{id}/finish` | Validate and store final values/ranking |

`race_simulation_horses` stores each session's original field, lane, seed, and
base values. It is needed because `race_simulations` in the source specification
does not otherwise retain the horse IDs selected at creation. The finish API checks
that the submitted result set exactly matches this field. Optional metric copies
are marked `is_simulated=true` and remain hidden from charts by default.

The matching base and real-time interpolation functions are
`RaceSimulationFormula` in Java and `simulatedVitals` in
`frontend/lib/head-trainer.ts`. Animation frames stay in the browser; only session
creation and final results call the backend.

## Schema migration

Migration `V12__head_trainer_and_race_simulations.sql` adds optional blood-pressure
fields and the simulation marker to training metrics, plus the simulation, field,
and result tables. The PostgreSQL vendor migration enables row-level security on
the three new simulation tables. Migration `V16` adds weekly workload and preferred
track surface to each training stage and distinguishes simulated race history from
official results. Recorded metrics are shown by default; a workspace toggle can
include simulation data. Heart-rate, speed (km/h), and stamina thresholds can be
configured with `HEAD_TRAINER_INJURY_ALERT_HEART_RATE`,
`HEAD_TRAINER_INJURY_ALERT_SPEED_KMH`, and `HEAD_TRAINER_INJURY_ALERT_STAMINA`.
# Stage workload guidance

Training plan create/update requests also accept `target_workload_minutes` (weekly workload target) and `target_track_surface` (preferred surface for the stage). When a session is linked to a plan and the request omits `track_surface`, the plan's preferred surface is copied to the session.
