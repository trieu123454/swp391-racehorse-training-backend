# Local frontend/backend API integration

Frontend: `http://localhost:3000`; backend: `http://localhost:8080`.

## Horse image

`GET /api/horses/{id}/image` is an authenticated JSON endpoint. Send
`Authorization: Bearer <accessToken>` using the same API client as other protected
requests. The JWT filter does not read authentication cookies or token query
parameters. CORS allows localhost:3000 and the Authorization header.

The response is `{ "hasImage": false }` or `{ "hasImage": true, "url": "..." }`.
For a private storage path, the backend generates a signed URL with `expiresIn: 300`.
For an already stored HTTP(S) image URL, it returns that URL without `expiresIn`.
Fetch this JSON first, then use the returned `url` as the image source. Do not put
the API endpoint itself into `<img src>` / Next Image: it is JSON and a normal image
request does not attach the API client's Bearer header. Do not append access tokens
to image URLs. Refresh the metadata when a signed URL expires.

Both VETERINARIAN and HEAD_VETERINARIAN can read horse images. HORSE_OWNER can
read only owned horses; GROOM can read only assigned horses within the configured
assignment window. Profile writes remain restricted to CLUB_MANAGER. Accounts
must be approved, not deleted, and have completed required password changes.
There is no club identifier or club membership guard in this repository's horse
model, so no club-scoped access guarantee is implemented here.

Missing/invalid Bearer credentials return 401. Ownership/assignment violations or
required password changes return 403. An accessible horse ID absent from the
catalog returns a business 404; this is different from a missing route.

## Unsupported frontend URLs

`GET /api/head-trainer/diet-records` is implemented as a read-only Head Trainer
catalog endpoint and returns `{ "data": [...] }`. Rows contain diet record fields,
`horse_name`, and nullable `doctor_name`, ordered by effective date descending
(null dates last) then ID descending. It includes historical records, excludes
soft-deleted horses, and returns `{ "data": [] }` when there are no records.
It requires an approved HEAD_TRAINER account with no pending password change.
The per-horse clinical and Groom endpoints retain their existing permissions.
No migration or new configuration is required for this endpoint.

The following routes are absent from both controller mappings and the running
backend OpenAPI specification:

- `GET /api/veterinarian/workload`
- `GET /api/vet/workload` (changing the prefix does not implement workload)

Authenticated calls reach Spring's resource fallback and return route 404, before
any corresponding business service runs. Do not substitute empty successful responses for missing workload functionality.

Existing veterinarian views include `GET /api/vet/health-overview`,
`GET /api/vet/stable-incidents`, and `GET /api/vet/calendar`. Shared clinical
endpoints accept both veterinarian roles. The calendar is not a workload report;
it must not be used as a drop-in replacement without a defined UI/data contract.
Owner registration and transfer request GET/POST routes are now implemented; see
[Horse Owner request API](horse-owner-profile-requests-api.md) for their input, output,
permissions, and migrations. Existing horse catalog/dashboard routes remain separate.

Until the workload contract is implemented, frontend must
show these features as unavailable and avoid fetching these unsupported routes.
No equivalent replacement URL currently exists. Check `/v3/api-docs` on the actual
backend instance when integrating a different backend version.

## Verification

### Horse Owner dashboard on V40

`GET /api/horse-owner/horses/{horseId}/dashboard?year=2026` reads videos from
`training_schedules.video_*` on consolidated V40 databases, aliasing the fields
to the existing video response (`id`, `training_schedule_id`, `video_url`,
`description`, `uploaded_at`, `uploaded_by_name`, `session_type`). Older schemas
continue to read `training_videos`. The query selects its source using schema
metadata, and never substitutes empty lists for SQL exceptions.

An owned horse without records returns empty health/training/video/race lists,
empty financial transactions/monthly summaries, and zero financial totals.
The `year` parameter continues to filter financial billing periods only; the
other sections retain their existing all-history behavior. Other owners receive
404, other roles 403, and unauthenticated callers 401. No migration is required.
`HorseOwnerDashboardTests` and `ConsolidatedHorseOwnerDashboardTests` exercise the
same empty/populated/authorization cases; the latter physically removes the
legacy video table in its isolated H2 database.

### Club Manager inventory and horse ownership history

`GET /api/club-manager/inventory-transactions?limit=100` returns `{data:[...]}`
with item, optional lot, and actor metadata. Limit must be 1 through 1000. It is
read-only and restricted to approved Club Managers. Supply request listing now
accepts `status=All` (no status predicate) and `PartiallyApproved` alongside the
existing Pending/Approved/Rejected filters; unknown statuses still return 400.

`GET /api/horses/{id}/ownership-history` returns an array directly, with previous
owner, new owner, and actor names. Managers and the current horse owner may read
it; other roles/owners are forbidden. An existing horse with no relational history
returns `[]`, and a missing horse returns 404.

V28.10 ensures the inventory catalog tables and fields used by these reads exist
on older schemas. V28.11 restores `horse_image_uploads`, removed by deployed V40
but required to record upload provenance and authorize profile image changes.
V28.12 enables PostgreSQL RLS on the compatibility tables. Existing catalog data
and horse images are not rewritten. These migrations use the existing out-of-order
Flyway setting; no new configuration is required.

### Training analysis on consolidated databases

The scheduled simulator also supports both layouts through `SimulationStorage`.
V40 removed `race_simulation_metrics` and `training_simulation_readings`: results
are on `race_simulation_horses` (`metric_id` marks a recorded result), and readings
are a JSON array in `race_simulations.readings_json`. Creation, detail, active
sessions, finalization, interruption and background maintenance use that layout
when present. Session row locks still protect sampling and exactly-once summaries;
group creation remains transactional. Invalid persisted JSON raises an error.
No migration or historical data rewrite is required for this fix.

Frontend contract: `GET /api/head-trainer/race-simulations/active-sessions` returns
`{data:[...]}`; `POST /api/head-trainer/race-simulations/group` accepts
`{sessions:[{horse_ids:[uuid],training_schedule_id:uuid,scenario?:string}]}` and
returns 201 with `{data:[...]}`. Static mappings are selected before `/{id}`;
malformed IDs return 400. Both operations require an approved Head Trainer.

`POST /api/head-trainer/reset-clean-data` has no backend mapping or alternate reset
route in this branch. The frontend calls a missing capability. Do not add a reset
implementation or redirect it to another destructive endpoint without defining
which records, dependencies, users and environments are in scope. No reset request
is needed to diagnose this mapping. Successful group creation is verified only in
isolated test data, not by creating sessions on the live database.

`GET /api/horses/{horseId}/training-analysis?from=YYYY-MM-DD&to=YYYY-MM-DD`
retains its `horse_id`, `from`, `to`, `weeks`, `sessions`, and `trial_runs` fields.
On V40, simulation results live in `race_simulation_horses` and are linked by
`metric_id`; the removed `race_simulation_metrics` table must not be queried.
The report checks schema metadata to select the appropriate result source for
both layouts. Consolidated participant rows without a `metric_id` have no result
yet and expose null metric fields. SQL failures are not converted to empty reports.
No migration or configuration change is needed. Head Trainer authorization and
date validation are unchanged. Regression coverage is in `HeadTrainerControllerTests`.

### Health examination and legacy image compatibility

The V40 deployed schema consolidated `health_exam_logs`, while this maintenance
branch still uses that table for examination edits. V28.8 restores the table and
its foreign keys/indexes if absent; PostgreSQL V28.9 enables RLS. These migrations
run with the existing `spring.flyway.out-of-order=true` setting. Existing exams,
inline history columns, and existing relational logs are not deleted or rewritten.
The inspected local database had no inline examination edit history to backfill.
Deployments with populated inline history require an explicit history conversion
before using this older branch; this migration does not convert that JSON.

`GET /api/horses/{id}/health-exams?page=1&limit=20` retains the
`{data,total,page,limit}` response. An existing horse without exams returns 200
with `data:[]` and `total:0`; an absent/deleted horse returns business 404.

Legacy markers `horses/cloudinary/<uuid>.<extension>` resolve to Cloudinary public
ID `horses/<uuid>`. The stored extension is not authoritative: the resolver reads
the asset metadata and returns its actual `secure_url`. Configure
`CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and `CLOUDINARY_API_SECRET` (or the
equivalent `app.cloudinary.*` properties). These are server credentials only.
The response remains `{hasImage:true,url:...}`; no credentials enter that response.
Missing referenced Cloudinary/Supabase objects return 404, provider failures return
502, and missing provider configuration returns 503. A horse with no image marker
still returns 200 with `{hasImage:false}`. Existing authorization checks run before
storage resolution.

Regression command:
`mvn '-Dspring.config.import=' '-Dtest=DatabaseMigrationTests,VeterinarianTests,HorseStorageTests,CloudinaryHorseImagesTests' test`.

`mvn -Dtest=VeterinarianTests test` includes real signed JWT requests for both
clinical roles, nonclinical role denials, account-state restrictions, owner and
groom image scope, Bearer-versus-cookie behavior, localhost CORS, image metadata,
and missing-route versus business 404 cases. Tests use an isolated H2 database.
