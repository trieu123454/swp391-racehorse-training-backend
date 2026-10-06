CREATE TABLE training_plan_horses (
    training_plan_id CHAR(36) NOT NULL,
    horse_id CHAR(36) NOT NULL,
    PRIMARY KEY (training_plan_id, horse_id),
    CONSTRAINT fk_training_plan_horses_plan FOREIGN KEY (training_plan_id)
        REFERENCES training_plans (id) ON DELETE CASCADE,
    CONSTRAINT fk_training_plan_horses_horse FOREIGN KEY (horse_id)
        REFERENCES horses (id) ON DELETE CASCADE
);

INSERT INTO training_plan_horses (training_plan_id, horse_id)
SELECT id, horse_id FROM training_plans;

-- Keep the original horse_id column as a compatibility anchor for existing clients;
-- plan membership is now defined by training_plan_horses.
ALTER TABLE training_plans DROP CONSTRAINT fk_training_plans_horse_id;
ALTER TABLE training_plans ALTER COLUMN horse_id DROP NOT NULL;
ALTER TABLE training_plans ADD CONSTRAINT fk_training_plans_horse_id
    FOREIGN KEY (horse_id) REFERENCES horses (id) ON DELETE SET NULL;

CREATE INDEX idx_training_plan_horses_horse_id ON training_plan_horses (horse_id);
