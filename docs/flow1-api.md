# Flow 1 — Backend API

Storage is Supabase, replacing Firebase by agreement. All endpoints require the existing Bearer access token and an APPROVED, non-deleted account. Database user IDs remain numbers; horse/stable IDs are UUID strings.

| Method | Endpoint | Permission / behavior |
| --- | --- | --- |
| GET | `/api/horses?page=0&size=20` | Manager/trainer: all active horses; owner: own horses only |
| GET | `/api/horses/{id}` | Same visibility; another owner's horse returns 403 |
| POST | `/api/horses` | Manager; create (201) |
| PUT | `/api/horses/{id}` | Manager; update basic profile |
| GET | `/api/horses/options/stables` | Manager; available boxes with free capacity |
| GET | `/api/horses/options/owners` | Manager; approved owners |
| GET | `/api/horses/{id}/deletion-warnings` | Manager; training and medical counts |
| DELETE | `/api/horses/{id}?confirmed=true` | Manager; soft delete (204) |
| POST | `/api/horses/images` | Manager; multipart field `file`, returns `imagePath` (201) |
| GET | `/api/horses/{id}/image` | Authorized viewer; signed URL valid 300 seconds, or `hasImage:false` |

Optional list filters (manager/trainer only): `search` (literal case-insensitive substring), `breed`, `currentStatus`, `stableBoxId`, `isTrainingLocked`. Filters combine with AND. Maximum page size 100. List response: `{items,total,page,size}`. Horse and option records use database snake_case keys; request fields use camelCase below.

```json
{
  "horseName": "Thunder",
  "breed": "Thoroughbred",
  "birthYear": 2020,
  "pedigreeFather": "Sire",
  "pedigreeMother": "Dam",
  "stableBoxId": "00000000-0000-0000-0000-000000000001",
  "ownerId": 17,
  "imagePath": null,
  "confirmOwnerChange": false
}
```

Stable selection is required. Owner/image and pedigree are optional. Update sends the full editable profile; null/omitted imagePath retains the existing image. Changing/removing the owner requires confirmOwnerChange=true (otherwise 409). Operational fields are never written from this request. Capacity admissions lock the stable row transactionally. No stable creation endpoint is included in Flow 1; populate the stable catalog through its management flow or controlled database setup.

Upload accepts JPG/JPEG, PNG, WebP <=5 MiB and checks extension, MIME and binary signature. Backend uses an API secret in the `apikey` header (never exposed to clients). Bucket remains private. The uploaded path is recorded against the uploader to reject arbitrary paths on create/update; DB image_url contains this permanent path, not an expiring URL. Upload errors do not modify the horse. Previous/orphaned images are retained per use case. Signed links already issued remain usable until their five-minute expiry, including after ownership changes/deletion.

Frontend integration: preview selected image locally, show upload progress, retain form on failure, allow saving without an image (or with the old one on edit). Only send returned imagePath after a successful upload. Request each authorized horse image endpoint for a fresh URL and use a placeholder on absent/error. Display confirmation before owner change/delete; redirect to the list with a message after detail 403. These UI behaviors are not implemented by the backend APIs.

Medical records currently have no open/closed status. Deletion warnings therefore expose total medicalRecords and activePrescriptions separately, plus scheduledTraining (Scheduled/InProgress). No fabricated open-status column is used. Soft delete retains all related records and storage objects. Flow 5 race history is deferred until Flow 5 is implemented.

Migrations V4/V5 add a private upload registry; existing auth tables and role model stay intact. Configure application-local.properties using the example; start with `mvn spring-boot:run` to migrate. Run `mvn test` for regression tests. Swagger: `/swagger-ui/index.html`.

Storage API reference: https://supabase.com/docs/reference/self-hosting-storage/introduction
