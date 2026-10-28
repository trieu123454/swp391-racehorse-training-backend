# Flow 1 — Horse Profile & Pedigree Management

## Purpose and boundary

Flow 1 creates the club's trusted horse profile and pedigree record for use by training, medical, care, owner, and reporting flows. The established responsibility is preserved: **Horse Owner submits a registration request with supporting evidence; Club Manager reviews and decides it.** The manager does not directly create a new registration. Flow 1 also covers profile viewing, manager corrections, private profile files, and a controlled link/ownership-transfer path. It does not define veterinary, training, race, or care records.

## Actors and record access

| Actor | Allowed actions | Record scope |
| --- | --- | --- |
| Horse Owner (approved account) | Submit `REGISTER` or `LINK`; see own requests, revisions, review notes, and files; correct only a `NEEDS_INFORMATION` request; see profiles currently owned; respond to a transfer request only while the horse is currently theirs | Own requests and horses with `owner_id = current user`; current-owner consent requests for those horses |
| Club Manager (approved account) | Review pending requests; request information, approve, or reject with a reason where required; choose a stable for a new horse; select a horse for a link; update existing basic profile/pedigree fields; soft delete with warning/confirmation; view ownership history | All profile data; review queue exposes `PENDING` requests and files, while the review response returns the handled request |
| Head Trainer and Veterinarian | View/list horse data needed by their existing modules | Existing operational visibility; no registration or profile write permission |
| Groom | View the minimal summary for assigned horses in the configured work window | Assigned horses only |
| System | Validate, store, record history/audit, issue private signed links, enforce stable capacity and identifier uniqueness | Enforced server-side, independent of UI controls |

All business endpoints require a valid token and an approved, non-deleted account. Role checks and record scoping are repeated on the backend. A frontend route or hidden button is not an authorization boundary.

## Preconditions and required data

- Horse Owner and Club Manager accounts must be approved and not soft deleted. The user must not be forced to change a password before business actions.
- Initial request has a required `requestType` (`REGISTER` or `LINK`), a horse name (2–100 characters), and 1–5 valid supporting documents. Existing requirements do not specify a mandatory document category.
- A document is at most 10 MiB and must match a PDF/JPEG/PNG/WebP binary signature and its supplied MIME type (or generic `application/octet-stream`). `OWNERSHIP_PROOF`, `HORSE_CERTIFICATE`, `HEALTH_DOCUMENT`, and `OTHER` are available review categories; certificate, health, and ownership-proof categories are not individually required by the existing contract.
- Horse image is optional; a provided image must be valid JPG/JPEG/PNG/WebP and at most 5 MiB.
- Breed, birth year, height, current weight, sire, dam, microchip, passport, and owner note are optional. Supplied birth year must be 1900 through the current year; height and weight are range/precision checked.
- A new `REGISTER` approval requires a selected available stable with capacity. Identifier values, when supplied, must be unique case-insensitively across existing horses and active `REGISTER` requests.
- A `REGISTER` profile owner is the authenticated submitting Horse Owner; clients cannot choose another owner. Legacy/unassigned existing horses may still have a null owner.
- For `LINK`, the manager must select an existing active profile. Supplied microchip/passport values must match values already present on that profile; absent profile identifiers can be filled on approval. A different current owner must consent before any ownership/profile mutation occurs.

Current requirements and schema do not define sex, full date of birth, coat color, or a separate registration number. These are not mandatory and are not collected. `birthYear` is the only birth-date field. Pedigree is currently two optional text fields (`pedigreeFather`, `pedigreeMother`), not a family-tree graph or pedigree certificate model.

## Main flow and alternatives

1. Horse Owner completes the request and uploads at least one supporting document, optionally categorized as ownership proof, horse certificate, health document, or other, plus an optional image and known identifiers.
2. Backend validates actor, required values, file count/type/size, optional numeric ranges, and registration identifier uniqueness. Files go to private storage; the request, file metadata, revision 1 history snapshot, and audit are persisted.
3. Club Manager sees pending requests, submitter details, categorized documents, and revision history. Manager can:
   - approve a `REGISTER`, selecting a stable; server locks and rechecks its status/capacity, creates the horse profile and ownership-history row, links the request, and notifies the owner;
   - approve a `LINK`, selecting an existing horse; identifiers are checked against that profile. If it is unowned or already belongs to the requester, the link is finalized. If another account owns it, the request waits for current-owner consent and does not mutate the horse yet;
   - request additional information with a note, moving to `NEEDS_INFORMATION`;
   - reject with a required reason, moving to terminal `REJECTED`.
4. Only the requesting owner may edit a `NEEDS_INFORMATION` request. Resubmission preserves old document rows, adds new documents under a new revision, stores a new snapshot, clears the prior review note, and returns to `PENDING`. Requester and request type are immutable.
5. For an ownership transfer, the currently recorded owner gets an in-app request. Only that owner can consent or decline. Consent updates `horses.owner_id`, writes ownership history and audit, and approves the request. Decline rejects the request and leaves the owner unchanged. A horse may have only one transfer request awaiting consent at a time.
6. Authorized users view profiles by their existing role scope. Owners are read-only on approved profiles. A manager may update basic profile and pedigree fields but cannot change owner through `PUT`; changes of owner go through the LINK/consent path. Managers may soft delete; related history and files are retained.

## Statuses and completion

| Status | Meaning | Allowed next action |
| --- | --- | --- |
| `PENDING` | New submission or resubmission awaiting manager review | Manager: request information, approve, reject |
| `NEEDS_INFORMATION` | Manager returned the request with a note | Requesting owner: edit and resubmit |
| `AWAITING_OWNER_CONSENT` | Manager reviewed a link to a horse with a different owner | Current owner: consent or decline |
| `APPROVED` | Horse profile created or link/transfer finalized | No more request edits/reviews |
| `REJECTED` | Manager rejection or current-owner decline | Terminal; a fresh request is needed |

Flow completion is a `REGISTER` request reaching `APPROVED` with a persisted horse, owner, stable, profile/pedigree values, IDs, and ownership history; or a `LINK` request reaching `APPROVED` with the intended existing profile and any transfer history finalized. `NEEDS_INFORMATION` and `AWAITING_OWNER_CONSENT` are intentionally incomplete states. Rejection is a completed decision but not a completed registration.

## Error and alternative outcomes

| Condition | Expected result |
| --- | --- |
| Missing horse name, missing initial supporting file, malformed numeric input, unsupported file content/category, or invalid year/range | 400; no request is committed |
| File over 10 MiB, or image over 5 MiB | 413; no request is committed |
| Duplicate microchip/passport on a new registration or identifier mismatch on LINK | 409; no profile mutation |
| No available stable or stable capacity filled before approval | 409; request remains `PENDING` for manager resolution |
| LINK targets a horse whose recorded owner's account is inactive or no longer has the Horse Owner role | 409; transfer is not started and the manager must resolve the request |
| Request is not `PENDING` when manager reviews, or is not `NEEDS_INFORMATION` when owner resubmits | 409; current state is preserved |
| Requesting owner accesses another owner's request, or a non-owner answers transfer consent | 404 for the scoped request lookup or 403 for the consent ownership check; no mutation |
| Horse Owner reads another owner's horse | 403; no horse data is returned |
| Link points to an inactive/missing horse | 404/409; request remains pending |
| Private storage upload/signing fails | Upload or gateway error; database transaction does not commit the request/profile |

An owner may read a pending request but cannot edit it until a manager changes it to `NEEDS_INFORMATION`. Approved profile edits belong to the Club Manager; there is no owner self-service profile update. A rejected request cannot be reopened or edited in place.

## Rules confirmed by project evidence vs decisions still open

**Confirmed in project requirements/schema or directly instructed by the user:** owner submits and manager reviews; managers own approved-profile edits; horse owner profile view is ownership-scoped and read-only; current profile fields include name, breed, birth year, height, current weight, sire, dam, image, stable, and owner; image/pedigree/owner were documented as optional on the original profile API; the user's policy answer adds a `NEEDS_INFORMATION` correction/resubmission route and requires current-owner consent for transfer; current schema has one `owner_id` and no co-owner relation. The database supports microchip/passport identifiers and the code validates duplicates when supplied.

**Not established, so not silently added:** official evidence standards/issuer verification; whether all link requests require manager-entered horse ID or allow alternative matching; appeal/reopen after rejection; transfer notification deadlines or reminder/escalation; co-ownership; owner-initiated edits after approval; mandatory registration certificate or health document; sex, full DOB, coat color, and separate registry-number fields; whether manager profile corrections require reason/effective-date versioning; image/document retention and deletion policy. The UI supports categories for optional certificates and health files, but does not make them mandatory.

## Audit gap table

Status records the implementation as found before this completion pass; the final column records the correction made in this pass.

| Requirement / rule | Responsible actor | Implementation evidence at audit | Status | Required correction / current outcome |
| --- | --- | --- | --- | --- |
| Owner submits new horse request; manager approves before profile creation | Owner; Manager | Registration request table/controller/service existed, but manager direct-create route was still available | Incorrect | Direct create now returns 403; new profile is created only while manager approves a submitted REGISTER |
| Required request fields and optional profile fields | Owner; backend | Request DTO/service validated name, year and metrics; image was required by panel despite optional API | Partial | Image now optional in UI/API; documented exact mandatory and optional fields |
| Supporting documents are required; categories and private document handling | Owner; storage/service | One-to-five docs and size/signature checks existed; no category or specific proof requirement | Partial | Keep at least one supporting document mandatory; add optional review categories and revision tagging; retain private storage and signed URLs |
| Breed, sex, birth, coat, identifiers, pedigree requirements are explicit | Owner; Manager | Only breed, birth year, metrics, pedigree and optional request identifiers were modeled; sex/full DOB/color/separate registration number absent | Partial | Preserve existing supported data; explicitly document the absent fields instead of treating them as mandatory |
| Identity values persist onto approved profile and reject duplicates | Backend; Manager | Request identifiers were not copied into horse schema/profile; no cross-request serialization for duplicate claims | Incorrect | Add profile columns, backfill from approved requests, case-insensitive checks, and serialized checks through a lock row |
| Request information with a manager note | Manager | Only PENDING/APPROVED/REJECTED existed | Missing | Add `NEEDS_INFORMATION`, required note, owner notification, history/audit |
| Owner correction/resubmission with history and retained documents | Owner | No resubmit endpoint or revision model | Missing | Add owner-scoped PATCH, immutable requester/type, incrementing revision and snapshots, retained categorized files |
| Approve REGISTER atomically with stable capacity and profile values | Manager; backend | Existing registration approval path; some fields and checks used direct SQL/custom data access | Partial | Persist all supported profile and identity fields via JPA entities; lock/recheck stable capacity and write ownership history |
| Reject with reason and block invalid state transitions | Manager | Reject existed, note not consistently required and no correction state | Partial | Require reason; permit review only from PENDING; terminal rejection and conflicts on repeat actions |
| LINK identity validation and safe transfer | Manager; current owner | Manager approval could directly change `owner_id`; no current-owner consent | Incorrect | Defer all mutations until consent; verify IDs, notify current owner, consent/decline endpoint, audit/history, one open consent request per horse |
| Owner can respond only for horse they currently own | Current owner; backend | No response endpoint or authorization rule | Missing | Query current ownership server-side and recheck under lock on every response |
| Owner sees only own horse/request data | Backend | Horse detail/list had server-side owner scope; request data owner filtering existed | Complete | Retained; added request/transfer paths also scope by current actor and ownership |
| Manager can update profiles and pedigree; owner is read-only | Manager; Owner | Manager update existed; ownership changes could be included in profile update | Partial | Keep basic/pedigree updates manager-only; reject owner changes with 409 and route through consent flow |
| Private profile image and approved documents | Owner; Manager; storage | Existing storage validation/signed URLs; registration panel made horse image mandatory | Partial | Make image optional; keep private signed access and show document category/revision |
| Review history, notifications, and audit | System; all actors | Audit mainly covered review; no request-revision history | Partial | Record submit, resubmit, info request, approval, rejection, and consent decisions plus in-app notifications |
| Role scope is enforced at server | All actors | Controller role annotations and service scope existed for core profile access | Partial | Add/retain role checks on new request and consent endpoints; unauthorized records return 403/404 without mutation |
| JPA layer approach and additive migration | Backend | Legacy registration and parts of `HorseService` still use `VetData`; new entities/repositories were added | Partial | New registration command and new schema use JPA/repositories and V32 additive migration. Legacy list/detail/owner search/warning reads still use existing `VetData`; that legacy access remains a known architecture gap |

## Implementation references

- Request orchestration: `src/main/java/com/example/springbootbackend/service/HorseRegistrationService.java`
- Owner/manager/consent endpoints: `HorseOwnerRegistrationController`, `HorseRegistrationReviewController`, `HorseOwnershipConsentController`
- Profile access/update: `HorseController` and `HorseService`
- Persistence: `HorseRegistrationRequest`, `HorseRegistrationDocument`, `HorseRegistrationRequestHistory`, `Horse`, and their repositories
- Safe schema extension: `src/main/resources/db/migration/V32__horse_registration_review_and_identity.sql`
- Frontend owner panel: `../swp391-racehorse-training-frontend/src/components/horse-owner/HorseRegistrationPanel.tsx`
- Frontend manager queue: `../swp391-racehorse-training-frontend/src/components/club-manager/ClubManagerWorkspace.tsx`
- Integration scenarios: `src/test/java/com/example/springbootbackend/controller/HorseRegistrationControllerTests.java`

## Remaining product decisions

1. Co-ownership is unsupported; the current-owner consent answer does not decide whether the product should later support multiple owners.
2. The club must define acceptable proof issuers/content and who verifies document authenticity. Current implementation enforces presence/type/size, not legal authenticity.
3. Confirm whether sex, full date of birth, coat color, and a formal registry number belong in a future approved profile schema.
4. Confirm whether managers need a reason and immutable correction history for post-approval profile/pedigree edits; current general audit records the update but does not snapshot every profile revision.
5. Define retention/deletion rules for private ownership, certificate, and health documents. Current behavior retains them with the request/history.
6. Define how a transfer should proceed when the current owner account is suspended, deleted, or otherwise unable to consent. The implementation blocks manager approval and requires manager resolution; it never bypasses consent.
