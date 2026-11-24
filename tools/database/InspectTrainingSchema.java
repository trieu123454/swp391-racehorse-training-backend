import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only schema diagnostics; never prints local credentials or application rows. */
class InspectTrainingSchema {
    public static void main(String[] args) throws Exception {
        var config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("application-local.properties"))) { config.load(reader); }
        String url = config.getProperty("spring.datasource.url", config.getProperty("SUPABASE_DB_URL"));
        if (args.length == 1 && args[0].equals("--transaction-pooler")) url = url.replace(":5432/", ":6543/");
        url += (url.contains("?") ? "&" : "?") + "connectTimeout=10&socketTimeout=20&prepareThreshold=0";
        try (var c = DriverManager.getConnection(url, config.getProperty("spring.datasource.username", config.getProperty("SUPABASE_DB_USER")), config.getProperty("spring.datasource.password", config.getProperty("SUPABASE_DB_PASSWORD")))) {
            c.setReadOnly(true);
            for (String sql : List.of(
                "SELECT table_name,column_name,data_type,column_default FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('race_simulations','race_simulation_horses','race_simulation_metrics','training_simulation_readings') ORDER BY table_name,ordinal_position",
                "SELECT version,description,success FROM public.flyway_schema_history WHERE version IN ('12','21','23','40','41') ORDER BY installed_rank",
                "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='public.race_simulation_horses'::regclass")) {
                try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
                    while (r.next()) {
                        for (int i=1;i<=r.getMetaData().getColumnCount();i++) System.out.print((i==1 ? "" : " | ")+r.getString(i));
                        System.out.println();
                    }
                }
            }
        }
    }
}
