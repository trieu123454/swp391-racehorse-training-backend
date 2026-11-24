package com.example.springbootbackend.horseowner;

import org.springframework.boot.test.context.SpringBootTest;

/** Run the same dashboard contract tests with V40's actual video storage layout. */
@SpringBootTest(properties={"spring.config.import=","spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa","spring.datasource.password=",
        "spring.datasource.url=jdbc:h2:mem:owner_dashboard_v40;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class ConsolidatedHorseOwnerDashboardTests extends HorseOwnerDashboardTests {
    @Override void prepareSchema() {
        db.execute("ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_id CHAR(36)");
        db.execute("ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_url VARCHAR(512)");
        db.execute("ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_description VARCHAR(255)");
        db.execute("ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_uploaded_by BIGINT");
        db.execute("ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_uploaded_at TIMESTAMP");
        db.execute("DROP TABLE IF EXISTS training_videos");
    }
    @Override void video(String schedule,String id) {
        db.update("UPDATE training_schedules SET video_id=?,video_url='https://example.com/video.mp4' WHERE id=?",id,schedule);
    }
}
