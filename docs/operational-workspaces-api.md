# Operational workspace API

All routes require an authenticated, approved account with the indicated role. Staff who must change their password must do so before using these routes.

## Groom

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/groom/my-horses` | List horses assigned within the 14-day past/future window; summary fields only |
| GET | `/api/groom/calendar?date=YYYY-MM-DD` | Read that day's assigned training sessions and care tasks |
| GET | `/api/groom/horses/{horseId}` | Read the assigned horse summary and training-lock state |
| GET | `/api/groom/horses/{horseId}/diet-records?active_on=YYYY-MM-DD` | Read only diets active on the requested date (today by default) |
| PATCH | `/api/groom/daily-tasks/{id}/complete` | Mark the Groom's own task complete; repeat calls are idempotent |
| PATCH | `/api/groom/training-schedules/{id}/complete` | Confirm an assigned training session after its scheduled end; updates the trainer's session status |
| POST | `/api/groom/horses/{horseId}/incidents` | Report an assigned horse incident; attach the uploaded image path if present |
| POST | `/api/groom/incidents/images` | Upload a JPG, PNG, or WebP incident photo (maximum 5 MB) as multipart field `file` |
| GET | `/api/groom/incidents?status=&horse_id=` | List only incidents submitted by the current Groom |
| GET | `/api/stable-incidents/{id}/image` | Return a short-lived signed URL; Groom can view only their own report, Veterinarian and Club Manager can review all reports |
| GET | `/api/groom/inventory?category=&low_stock_only=false` | Read shared inventory and low-stock flags |
| POST | `/api/groom/supply-requests` | Submit a supply request; duplicate pending requests return a warning |
| GET | `/api/groom/supply-requests?status=` | List only requests submitted by the current Groom |
| GET | `/api/groom/workspace?date=YYYY-MM-DD` | Combined summary of assigned horses and the daily calendar |

Groom may confirm only sessions assigned to their account. The API rejects early confirmation and sends the Head Trainer who created the session a notification. Training plans and veterinarian records remain read-only for this role.
The assignment horizon defaults to 14 days before and after today and can be adjusted with `app.groom.assignment-window-days`.
Uploaded paths are private. The incident record accepts an upload path created by the current Groom; the UI no longer asks Groom to paste an arbitrary image URL.

## Notifications

`/api/notifications` is shared across roles. Groom, Club Manager, Head Trainer, and Horse Owner workspaces show the current user's notifications, refresh them every minute, and allow marking one or all as read. Notifications cover task and session assignment/completion, supply-request decisions, incident reports/resolution, veterinary health exams and training locks, training videos, and official race results.

## Veterinarian incident intake

`GET /api/vet/stable-incidents?status=Pending` lets Veterinarians review Groom reports, including the horse's box, current health/readiness, and attached image URL.

## Club Manager

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/club-manager/operations-report?from=&to=` | Health, staffing, training performance, care-task, incident, operating-cost, and official-race summary |
| GET | `/api/club-manager/races` | List official races, excluding simulation races |
| POST | `/api/club-manager/races` | Add an official race to the catalog for Head Trainer registration |
| GET | `/api/club-manager/audit-logs?from=&to=&page=&limit=` | Paginated audit history |
| GET | `/api/club-manager/grooms` | Active Groom accounts eligible for assignment |
| GET | `/api/club-manager/groom-tasks?date=` | Daily care assignments |
| POST | `/api/club-manager/groom-tasks` | Assign a `Feeding`, `Cleaning`, `Bathing`, or `IceBath` task |
| PATCH | `/api/club-manager/groom-tasks/{id}` | Reassign a pending task (`{"groom_id":123}`) or cancel it (`{"action":"Cancel"}`) |
| GET | `/api/club-manager/groom-incidents?status=Pending` | List incidents for review |
| PATCH | `/api/club-manager/groom-incidents/{id}/resolve` | Resolve a pending incident |
| POST | `/api/club-manager/financial-transactions` | Record an Owner-visible `Care`, `Medical`, `Prize`, or `Other` transaction |

Club Manager lock and role-change operations return a conflict while a Groom has future training sessions or pending care tasks. Reassign or cancel those assignments first.

## Head Trainer data shared with Horse Owner

| Method | Route | Purpose |
| --- | --- | --- |
| PATCH | `/api/race-entries/{entryId}/result` | Record official placing and prize after race day |
| POST | `/api/training-schedules/{id}/videos` | Share an HTTPS video link with the Horse Owner |

Video shares and recorded official race results notify the Horse Owner linked to the horse. The owner's dashboard also refreshes its data every 30 seconds.

Race simulation finish requests no longer submit result metrics. The backend derives speed, finish time, heart rate, and blood pressure from the saved seed and simulation profile. The `shared_training_metrics` read model exposes both recorded training metrics and simulated race results, with `is_simulated` identifying generated data.
# Club Manager inventory and supply review

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/club-manager/inventory-items` | List inventory with low-stock flags |
| POST | `/api/club-manager/inventory-items` | Add an item and its initial stock |
| PATCH | `/api/club-manager/inventory-items/{id}` | Update item details, stock, or reorder threshold |
| GET | `/api/club-manager/supply-requests?status=Pending` | Review Groom replenishment requests |
| PATCH | `/api/club-manager/supply-requests/{id}` | Approve or reject a pending request (`{"action":"Approve"}` / `{"action":"Reject"}`) |

Approving a request records the decision and notifies its Groom. The manager updates inventory quantity when the requested goods have physically arrived.

The operations report also returns `training_performance` (including `in_progress`), `operating_costs_and_prize_income` (including `other_amount`), and `official_competition` for the selected date range. Financial totals follow `billing_period` by month, matching the Horse Owner report. Simulated races are excluded from official competition totals.

# Groom nutrition, inventory, and incident history

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/groom/horses/{horseId}/diet-records?active_on=YYYY-MM-DD` | View approved diet records active on a date |
| GET | `/api/groom/inventory` | View stock and low-stock indicators |
| POST | `/api/groom/supply-requests` | Submit a replenishment request |
| GET | `/api/groom/supply-requests` | View the Groom's requests and decisions |
| GET | `/api/groom/incidents` | View the Groom's incident reports and attached image URLs |
