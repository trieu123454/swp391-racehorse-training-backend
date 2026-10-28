# Flow 1 — Horse Profile & Pedigree Management

All endpoints require a Bearer token and an approved, non-deleted account. Horse and stable IDs are UUID strings; user IDs are numeric. Registration is owner-submitted and manager-reviewed. `POST /api/horses` remains only as a compatibility route and returns 403; it cannot create a horse directly.

## Registration requests

| Method | Endpoint | Actor and behavior |
| --- | --- | --- |
| GET | `/api/horse-owner/horse-registration-requests` | Horse Owner; own requests, documents, history, and current status only |
| POST | `/api/horse-owner/horse-registration-requests` | Horse Owner; submit `REGISTER` or `LINK` request as multipart |
| PATCH | `/api/horse-owner/horse-registration-requests/{id}` | Requesting Horse Owner; correct a `NEEDS_INFORMATION` request and resubmit it |
| GET | `/api/club-manager/horse-registration-requests` | Club Manager; pending requests and supporting files |
| PATCH | `/api/club-manager/horse-registration-requests/{id}` | Club Manager; approve, request more information, or reject a pending request |
| GET | `/api/horse-owner/ownership-transfer-requests` | Current Horse Owner; requests awaiting their consent for a horse they currently own |
| PATCH | `/api/horse-owner/ownership-transfer-requests/{id}/consent` | Current Horse Owner only; `CONSENT` finalizes transfer or `DECLINE` rejects it |

The multipart `POST` and correction `PATCH` contain a JSON `request` part, optional `horseImage`, zero or more `documents`, and an optional JSON `documentTypes` array aligned by file order. Initial submissions require one to five supporting documents; current requirements do not specify that a particular document category is mandatory. Categories are `OWNERSHIP_PROOF`, `HORSE_CERTIFICATE`, `HEALTH_DOCUMENT`, and `OTHER` and help manager review. Each document is at most 10 MiB and must pass private storage MIME/signature validation. Horse images are optional; when provided, they must pass the existing JPG/JPEG, PNG, or WebP check and be at most 5 MiB. A correction may retain existing files and add up to five files.

Request body fields:

```json
{
  "requestType": "REGISTER",
  "horseName": "Thunder",
  "breed": "Thoroughbred",
  "birthYear": 2020,
  "heightCm": 162.5,
  "currentWeightKg": 452.0,
  "pedigreeFather": "Sire",
  "pedigreeMother": "Dam",
  "microchipNumber": "982000000000001",
  "passportNumber": "PASS-001",
  "ownerNote": "Supporting context"
}
```

`horseName` and one or more supporting documents are required. Breed, birth year, height, weight, pedigree names, microchip, passport, image, and owner note are optional. A supplied birth year cannot be before 1900 or after the current year; height and weight are range/precision validated. Microchip and passport values must be unique case-insensitively across active horses and active `REGISTER` requests. Sex, full date of birth, coat color, and a separate registry number are not defined by current requirements or schema and are not collected.

Request statuses: `PENDING` → `NEEDS_INFORMATION` → `PENDING` (new revision), `PENDING` → `APPROVED` or `REJECTED`; a `LINK` request reviewed for a horse with a different current owner goes to `AWAITING_OWNER_CONSENT`, then to `APPROVED` on current-owner consent or `REJECTED` on decline. Rejection is terminal. A manager must include a note when requesting information or rejecting. A current owner cannot consent to a request for a horse they no longer own. Only the requesting owner may resubmit; request type and requester cannot change.

For `APPROVE` of a `REGISTER` request, body includes `stableBoxId`; the server locks the box, checks active status/capacity, creates the horse, copies profile, pedigree, microchip/passport and image, sets the request owner, records ownership history and audit, then notifies the owner. For `APPROVE` of a `LINK`, body includes `horseId`; identifiers supplied must match the selected profile when already present. An unowned horse or same-owner link is approved directly. For another owner’s horse, manager approval only starts the consent step: no profile owner or identity changes until the current owner consents. A horse can have only one transfer request awaiting consent at a time.

## Profiles, pedigree, and records

| Method | Endpoint | Permission / behavior |
| --- | --- | --- |
| GET | `/api/horses?page=0&size=20` | Manager/Head Trainer/Veterinarian: all active horses; Groom: assigned horses in the configured work window; owner: own horses only |
| GET | `/api/horses/{id}` | Same record scope; another owner receives 403 |
| POST | `/api/horses` | Club Manager route retained but disabled with 403; use reviewed owner registration |
| PUT | `/api/horses/{id}` | Club Manager updates basic profile, pedigree, stable, identity identifiers, and image; changing owner returns 409 |
| GET | `/api/horses/{id}/ownership-history` | Club Manager only |
| GET | `/api/horses/options/stables` | Authenticated user; available boxes with free capacity |
| GET | `/api/horses/options/owners?q=tri&page=0&size=20` | Club Manager; legacy paged owner lookup; does not authorize owner changes |
| GET | `/api/horses/{id}/deletion-warnings` | Club Manager; training and medical counts |
| DELETE | `/api/horses/{id}?confirmed=true` | Club Manager; soft delete |
| POST | `/api/horses/images` | Club Manager; multipart `file`, returns private `imagePath` |
| GET | `/api/horses/{id}/image` | Authorized profile viewer; signed URL, or `hasImage:false` |

Profile `horseName` is required. Breed, birth year, height, weight, pedigree, image, microchip, and passport are optional. For `REGISTER`, profile owner is set to the submitting Horse Owner and is not client-selectable; legacy/unassigned profiles may still have no owner. Stable is selected by the manager for a new registration and is required on an existing profile update. Pedigree is represented by the optional father and mother names; there is no separate pedigree-document or ancestry graph model. Managers can correct approved profile fields; owners have read-only profile access and can only edit a pending request returned for information. Ownership changes use `LINK` plus current-owner consent, never the profile update endpoint. The database has one `owner_id`; co-ownership is not supported.

Optional list filters for managers/trainers: `search`, `breed`, `currentStatus`, `stableBoxId`, and `isTrainingLocked` (AND-combined, maximum page size 100). Horse data uses database snake_case keys. Updates are full profile payloads; null/omitted image retains the existing image. Existing operational health/training fields are not written by profile updates. New horse admission and stable-capacity checks run transactionally with row locks. No stable creation endpoint is part of Flow 1.

Horse image and registration documents use private storage. Database rows store permanent object paths; clients receive short-lived signed links from authorized endpoints. Supabase credentials remain server-side. Upload failures must not create a persisted request/profile. Audit and request history retain submission, correction, review, and consent decisions; document rows carry revision and category.

## Existing profile API notes

Owner lookup returns `{items, hasMore, page, size}` and does not expose the full directory. Enter at least three characters for name/email/phone search; numeric owner ID (also `#123`) can be searched directly. PostgreSQL trigram indexes support substring lookup. Medical records do not currently have an open/closed status; soft delete retains related records and storage objects. Flow 5 race history remains deferred.

Migrations are additive; do not edit applied migrations. Configure local storage credentials outside source control, run `mvn spring-boot:run` to migrate, and `mvn test` for backend checks. Swagger: `/swagger-ui/index.html`.
