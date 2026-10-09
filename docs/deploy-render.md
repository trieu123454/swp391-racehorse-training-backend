# Deploy backend to Render

## Create the service

1. Push this project, including `render.yaml` and `Dockerfile`, to the Git repository you want Render to deploy.
2. In Render, create a new Blueprint and connect that repository. Render reads `render.yaml` and builds the Docker image from the repository root.
3. During Blueprint setup, enter the environment variables listed below. Render generates `AUTH_JWT_SECRET` for the service.
4. Deploy, then check `https://<service-name>.onrender.com/api/health` and open `https://<service-name>.onrender.com/swagger-ui/index.html`.

## Environment variables

Use the matching values from your existing private `application-local.properties` or Supabase project settings. Do not put credentials in `render.yaml` or commit a local properties file.

- `SUPABASE_DB_URL`: JDBC URL for the project's PostgreSQL database, including `sslmode=require`. For the Supabase transaction pooler, use its PostgreSQL URL and port.
- `SUPABASE_DB_USER`: database user, including the project suffix if Supabase shows one.
- `SUPABASE_DB_PASSWORD`: database password.
- `APP_CORS_ALLOWED_ORIGINS`: comma-separated frontend origins, such as `https://your-frontend.example.com` (no trailing slash).
- `SUPABASE_URL`: Supabase project URL, needed for Supabase Storage features.
- `SUPABASE_SECRET_KEY`: backend secret/service-role key, needed for Supabase Storage features. Keep it private.
- `GOOGLE_CLIENT_ID`: add this in the Render service settings if Google sign-in is enabled.

Render supplies `PORT` at runtime; the Spring Boot configuration uses it while preserving the local `SERVER_PORT` setting and default port. The health check uses the public `/api/health` endpoint.

The service connects to the Supabase database configured above. Confirm that this database has the migrations required by the application before deploying.
