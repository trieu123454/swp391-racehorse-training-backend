# Horse Owner portal API

The read-only owner portal is backed by the existing horse, health, training,
race, video, and financial report tables. It does not create sample records.

## Endpoints

- `GET /api/horse-owner/horses` returns the signed-in owner's active horses,
  pedigree, height, current health/readiness fields, weight, and stable location.
- `GET /api/horse-owner/horses/{horseId}/dashboard?year=2026` returns that
  horse's health measurements, weight/performance log, training calendar,
  uploaded training videos, race entries/results, and the selected year's
  financial summary and transactions.

Both endpoints require a signed-in, approved `HORSE_OWNER` account. Every horse
query is constrained by the authenticated user's `owner_id` and excludes
soft-deleted horses. An ID belonging to another owner returns 404.

The dashboard uses existing data: body weight and performance from
`training_metrics_logs`; examination measurements from `health_exams`; training
dates from `calendar_events` linked to `training_schedules`; clips from
`training_videos`; results and prize amounts from `horse_race_entries` and
`races`; billing amounts from `financial_reports`.

Financial report types containing prize/reward/bonus/award terms are grouped as
prize income; medical/health/veterinary terms are grouped as medical costs; and
care/feed/food/boarding/stable terms are grouped as care costs. Unrecognized
types remain visible as “other” and are not included in classified expenses.
The owner portal refreshes the selected horse dashboard every 30 seconds.
