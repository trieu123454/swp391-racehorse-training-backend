# Horse Owner profile requests

All endpoints require `Authorization: Bearer <accessToken>`, the current database
role HORSE_OWNER, an APPROVED non-deleted account, and no required password change.
The caller identity is taken from the authenticated account, never from the body.

## List requests

- `GET /api/horse-owner/horse-registration-requests`
- `GET /api/horse-owner/ownership-transfer-requests`

Both return HTTP 200 and `{ "items": [], "total": 0 }` when this owner has no
requests. Otherwise `items` contains this sender's persisted requests, newest first;
`total` is the number of items. Lists currently return all items without pagination.
They do not include another owner's requests or incoming transfers for the recipient.

Registration items have `id`, `requester_id`, `horse_name`, `breed`, `birth_year`,
`pedigree_father`, `pedigree_mother`, `height_cm`, `current_weight_kg`, `reason`,
`status`, and `created_at`. Optional properties are null when omitted.
Transfer items have `id`, `requester_id`, `horse_id`, `horse_name`, `new_owner_id`,
`new_owner_email`, `reason`, `status`, and `created_at`.
Dates are UTC ISO 8601 strings. Request IDs and horse IDs are UUID strings; user IDs
are numeric. Newly created request status is `PENDING`.

## Submit registration

`POST /api/horse-owner/horse-registration-requests` with `Content-Type: application/json`:

```json
{
  "horse_name": "Thunder",
  "breed": "Thoroughbred",
  "birth_year": 2020,
  "pedigree_father": "Sire",
  "pedigree_mother": "Dam",
  "height_cm": 165.0,
  "current_weight_kg": 480.50,
  "reason": "Request to register my horse"
}
```

Only `horse_name` is required (2?100 characters after trimming). Optional birth year
is between 1900 and the current business year, height 50?250 cm (one decimal), weight
100?900 kg (two decimals), breed up to 50 characters, pedigree fields up to 100,
and reason up to 2000. Camel case versions of snake case fields are also accepted;
`notes` is an alias for `reason`.

HTTP 201 returns the created registration item directly, including its generated
`id`, authenticated `requester_id`, `status: "PENDING"`, and UTC `created_at`.
No horse is created and no stable box is reserved by this submission.

## Submit ownership transfer

`POST /api/horse-owner/ownership-transfer-requests`:

```json
{
  "horse_id": "7f8516bd-2d8e-4c1f-ab50-4c354f427a7e",
  "new_owner_email": "recipient@example.com",
  "reason": "Request ownership transfer"
}
```

`horse_id` and `new_owner_email` are required. The sender must currently own the
non-deleted horse. The recipient must be a different active approved HORSE_OWNER
without a required password change. Only one pending transfer per horse is allowed;
submissions lock the horse row while checking and inserting. Camel case fields
`horseId` and `newOwnerEmail` are also accepted.

HTTP 201 returns the created transfer item directly. Horse ownership remains
unchanged while the request is pending. Review/approve/reject endpoints are outside
this change; the frontend must not claim that registration or transfer has been
approved after submission.

## Errors and integration

- 400: invalid input, invalid recipient, self transfer, or future birth year.
- 401: missing/invalid JWT or account no longer authenticated by the JWT filter.
- 403: wrong role, unmet password change, or attempting to transfer another owner's horse.
- 404: requested horse does not exist or is deleted (POST transfer only).
- 409: a pending transfer already exists for this horse.

Business errors contain `{ "message": "..." }`. Bean validation errors additionally
contain `errors: [{ "field": "...", "message": "..." }]`.

Frontend should fetch both lists through its authenticated API client, render an
empty state for empty `items`, and POST to the same URL to submit each form. Read
the returned object directly on creation, then refetch the list or prepend it.
Do not send `requester_id`, `owner_id`, `status`, or approval fields; they cannot
change the authenticated sender or server-generated pending status.

The original Flyway V27/V28 files are restored unchanged from Git, and registration
requests use the existing `horse_registration_requests` table (including previous
submissions). V28.1 adds pending outgoing transfers; PostgreSQL V28.2 enables row
level security for the request/document tables. No existing request data is copied
or erased. The connected database was
already V40. Out-of-order migration support is now enabled in application.properties:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.flyway.out-of-order=true"
```

This uses Flyway out-of-order execution for additive migrations, without disabling
validation or repairing/deleting history. Normal `mvn spring-boot:run` works after
the additions are installed. Do not restore/run V29?V40 as fresh migrations on this
maintenance branch: V40 removes tables still used by its other legacy modules.
The API uses the established backend database connection with appropriate privileges.


## Multipart registration and supplements

POST also accepts `multipart/form-data`; PATCH `/api/horse-owner/horse-registration-requests/{id}`
accepts JSON or multipart. GET the same ID to refresh signed URLs. All ID operations
are owner scoped (404 for another owner). PENDING and NEEDS_INFORMATION requests can be supplemented (409 otherwise).
Supplements increment revision_no and resubmit the request as PENDING. Existing review history is preserved.

Multipart parts:
- `request`: JSON Blob, Content-Type `application/json`. Required on POST, optional on PATCH.
- `horseImage`: optional JPG/PNG/WebP, at most 5 MiB.
- `documents`: repeated file parts, at most five per call, PDF/JPG/PNG/WebP, at most 10 MiB each.
- `documentTypes`: JSON Blob, Content-Type `application/json`, an ordered string array
  matching documents exactly: OWNERSHIP_PROOF, HORSE_CERTIFICATE, HEALTH_DOCUMENT, OTHER.

`request` accepts horseName, breed, birthYear, heightCm, currentWeightKg,
pedigreeFather, pedigreeMother, microchipNumber, passportNumber, ownerNote and
requestType (REGISTER or LINK; defaults to REGISTER). Existing snake case names
and reason/notes remain accepted. Omitted or null PATCH fields retain existing values;
use an empty string to clear optional text. PATCH appends documents and replaces
the current image. Prior objects are retained so an issued signed URL remains valid
until its five minute expiry. Request type LINK is persisted; submission does not
itself link or approve an existing horse.

Responses retain the existing snake case properties, plus `owner_note`,
`request_type`, `microchip_number`, `passport_number`, `horse_image_path`,
`horseImageUrl` (also `horse_image_url`), `documents: []` and `history: []`.
Documents include id, request_id, document_type, original_filename, content_type,
file_size, uploaded_at, object_path and url. History entries include id, actor_id,
action (SUBMITTED/SUPPLEMENTED), created_at. Arrays are always present on list,
detail, POST and PATCH; older requests can have an empty history.
URLs are signed for 300 seconds. Refresh via GET when expired. Do not construct
public object URLs. Client code must let the browser set the multipart boundary:

```javascript
const form = new FormData();
form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
if (horseImage) form.append('horseImage', horseImage);
documents.forEach(file => form.append('documents', file));
form.append('documentTypes', new Blob([JSON.stringify(documentTypes)], { type: 'application/json' }));
await fetch('/api/horse-owner/horse-registration-requests', {
  method: 'POST', headers: { Authorization: `Bearer ${accessToken}` }, body: form
});
```

Invalid types/content/extensions, empty files, type count mismatch or >5 documents
return 400; oversize files return 413. Files are all validated before any upload.
Database rollback triggers cleanup of uploaded objects when Storage or persistence fails.
Storage failures return 502/503, without pretending the submission succeeded.

### Storage and restarting

V28.3 adds document type metadata and persistent registration history. V28.4 enables
history RLS and provisions the private Supabase bucket `horse-registration-files`
(10 MiB limit; the four allowed MIME types). No client storage policies are created;
only the backend server key accesses these objects. Existing public image bucket
settings are untouched. If a bucket with this name already exists as public,
uploads fail explicitly until an administrator makes it private.
`SUPABASE_REGISTRATION_BUCKET` overrides the bucket name; provision an overridden
bucket as private yourself. Existing SUPABASE_URL and SUPABASE_SECRET_KEY are used.
The PostgreSQL migration skips bucket provisioning on non-Supabase installations.

Out-of-order Flyway migrations are enabled for this maintenance branch because the
connected database is already V40 and this branch contains V28.x compatibility fixes.
Existing migration checksums remain validated; V27/V28 are unchanged.
Stop the old backend with Ctrl+C, then run `mvn spring-boot:run`. Flyway runs the new
migrations at startup. Check `/api/health` and `/swagger-ui/index.html`.

Validation: `mvn test "-Dspring.datasource.url=jdbc:h2:mem:verification;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.driver-class-name=org.h2.Driver" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="`.
Use these overrides because the existing test profile points to a shared database.
`HorseRegistrationMultipartTests` sends real multipart HTTP
requests through embedded Tomcat, with a local HTTP Storage fixture and isolated H2
database. It covers image + multiple documents, supplements, ownership and role checks,
invalid files, parser size limits, public bucket rejection, storage failure and rollback.
The frontend source is not present in this backend workspace, so compatibility is
verified against the supplied FormData contract and the existing JSON API tests.


### Verified on 2026-11-16

- 32 relevant automated tests passed: owner requests (12), real HTTP multipart (4),
  migrations (6), OpenAPI (2), existing horse controller/storage (8).
- Spring context startup passed separately (1).
- Actual localhost:8080 + Supabase PostgreSQL/Storage smoke test passed: POST 201 with
  a PNG image and two documents; downloaded signed URLs matched uploaded bytes;
  PATCH 200 appended a third document and persisted revision 2; invalid file 400;
  unauthenticated public object access rejected. Temporary account, requests and
  Storage objects were deleted after verification.
- The broader test run has an unrelated existing error in
  AuthControllerTests.horseOwnerCanRegisterLoginAndRefresh: a pessimistic-lock
  repository query is called by the test outside a transaction. PostgreSQL-only
  integration suites are skipped without their explicit environment flags.
- The existing scheduled race simulator logs a missing race_simulation_metrics table
  on this V40 database. Registration endpoints and health checks work; this change
  does not repair the separate simulator/schema mismatch.

Files changed for this work:
- src/main/java/com/example/springbootbackend/horseowner/controller/HorseOwnerRequestController.java
- src/main/java/com/example/springbootbackend/horseowner/controller/HorseOwnerExceptionHandler.java
- src/main/java/com/example/springbootbackend/horseowner/dto/HorseRegistrationRequest.java
- src/main/java/com/example/springbootbackend/horseowner/service/HorseOwnerRequestService.java
- src/main/java/com/example/springbootbackend/horseowner/service/RegistrationStorage.java
- src/main/resources/application.properties
- src/main/resources/db/migration/V28_3__registration_attachments_and_history.sql
- src/main/resources/db/postgresql/V28_4__secure_registration_history.sql
- src/test/java/com/example/springbootbackend/horseowner/HorseOwnerRequestTests.java
- src/test/java/com/example/springbootbackend/horseowner/HorseRegistrationMultipartTests.java
- src/test/java/com/example/springbootbackend/DatabaseMigrationTests.java
- docs/horse-owner-profile-requests-api.md


## Club Manager registration review

`GET /api/club-manager/horse-registration-requests` requires an active, approved
CLUB_MANAGER with no mandatory password change. The default queue includes PENDING
and NEEDS_INFORMATION, ordered oldest first. Optional `status=PENDING` or
`status=NEEDS_INFORMATION` filters the queue; invalid status is 400.
Response: a JSON array directly, `[]` when empty. The Club Manager frontend uses
`requests.map(...)`, so this endpoint does not wrap the list in items/data/total.
Each item retains the owner registration fields and signed file URLs, with
`owner_id`, `requester_id`, `owner_name`, `owner_email`, `owner_phone` and
`owner: { id, full_name, email, phone }`. documents and history are always arrays.
Final APPROVED/REJECTED requests are excluded from the queue.

`PATCH /api/club-manager/horse-registration-requests/{id}` accepts application/json:

```json
{
  "action": "APPROVE",
  "stableBoxId": "00000000-0000-0000-0000-000000000001",
  "horseId": null,
  "reviewNote": "Ownership documents verified"
}
```

Actions:
- APPROVE + REGISTER: stableBoxId is required; omit horseId. Creates the horse from
  the submitted profile, including identity numbers and private image; assigns the
  requester and stable, and records ownership history.
- APPROVE + LINK: horseId is required. Links an unowned horse or confirms the same
  existing owner. A horse belonging to another owner returns 409 and must use the
  ownership transfer workflow. stableBoxId may be omitted if the horse already has
  a stable; submitted registration fields do not overwrite existing horse data.
- REQUEST_INFORMATION: reviewNote is required, status becomes NEEDS_INFORMATION.
  The owner can supplement their request via the existing owner PATCH, which
  resubmits it as PENDING and preserves review history.
- REJECT: reviewNote is required, status becomes REJECTED.

For non-approval actions omit stableBoxId and horseId. reviewNote is at most 2000
characters. Snake case aliases stable_box_id, horse_id and review_note are accepted.
The response is the updated request directly, including approved_horse_id,
reviewed_by, reviewed_at, review_note, status, files and history. History records
include action, actor_id, review_note, from_status, to_status, created_at.
Only PENDING/NEEDS_INFORMATION requests can be reviewed; repeated final decisions
return 409. A valid unknown request ID is 404; invalid UUID/action/body is 400.
Unauthorized callers get 401 and other roles or restricted manager accounts get 403.

Request and stable rows are locked while deciding. A stable must be Available and
have capacity; LINK excludes the existing horse when checking occupancy. Approval,
request status, review history, ownership history, audit record and owner notification
commit atomically. Failed validation or URL signing rolls back the entire decision.
Registration images continue to work through the existing owner-scoped
`GET /api/horses/{id}/image` endpoint after approval.

V28.5 adds review note/from/to status columns to registration history and ensures
horse identity columns exist on both fresh databases and the existing V40 schema.
V28.6 also restores the ownership history table removed by the deployed V40
consolidation, and PostgreSQL V28.7 enables its RLS. No previously applied migration
is edited. Restart with `mvn spring-boot:run`;
the existing out-of-order migration setting applies these migrations on V40.

Frontend source is absent from this workspace. Compatibility is checked against
the requested routes/body and existing list conventions, not a running frontend UI.
Swagger documents both operations, bearer authentication and the action enum.
`HorseRegistrationReviewTests` covers queue responses with signed files, all three
actions, supplementation and re-review, final-state conflicts, stable capacity,
LINK ownership protection, account/role restrictions, image access and OpenAPI.


Club Manager validation on 2026-11-16: the 47 relevant tests passed (10 review,
12 owner requests, 4 real HTTP multipart, 6 migration, 5 existing manager,
6 horse controller, 2 storage, 2 OpenAPI). After discovering the V40 ownership table
removal during the live smoke test, the 16 review/migration tests were rerun and passed
with V28.6/V28.7. The compatibility test explicitly drops the V27 ownership table to
model the deployed V40 schema before applying the new migrations.

Files changed for Club Manager review:
- src/main/java/com/example/springbootbackend/clubmanager/controller/ClubManagerController.java
- src/main/java/com/example/springbootbackend/clubmanager/dto/ReviewHorseRegistrationRequest.java
- src/main/java/com/example/springbootbackend/clubmanager/service/HorseRegistrationReviewService.java
- src/main/java/com/example/springbootbackend/clubmanager/exception/ClubManagerExceptionHandler.java
- src/main/java/com/example/springbootbackend/horseowner/service/HorseOwnerRequestService.java
- src/main/java/com/example/springbootbackend/horse/storage/HorseStorage.java
- src/main/resources/db/migration/V28_5__club_manager_registration_review.sql
- src/main/resources/db/migration/V28_6__ensure_registration_ownership_history.sql
- src/main/resources/db/postgresql/V28_7__secure_registration_ownership_history.sql
- src/test/java/com/example/springbootbackend/clubmanager/HorseRegistrationReviewTests.java
- src/test/java/com/example/springbootbackend/DatabaseMigrationTests.java
- docs/horse-owner-profile-requests-api.md


Final live localhost:8080 + PostgreSQL smoke test passed after applying V28.5?V28.7:
manager queue GET 200, REQUEST_INFORMATION 200, owner supplementation 200, APPROVE
200 with a created horse and stable assignment, REJECT 200, repeated approval 409,
and Horse Owner access to the manager queue 403. Temporary manager/owner accounts,
requests, horse, stable, review/ownership history, notifications and audit records
were cleaned up. The restarted backend remains running and /api/health returns UP.

The Club Manager queue response was corrected to a root JSON array after the frontend
reported `requests.map is not a function`. Tests assert arrays for populated and empty queues.

Array response fix verification: 17 tests passed (10 registration review, 5 existing
manager, 2 OpenAPI), including root-array assertions for populated and empty queues.
