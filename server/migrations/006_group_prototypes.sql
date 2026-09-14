-- Dispersed prototype faces per person group (5-10 canonical looks).
-- Dual-gate clustering: a new face joins only when max-sim vs ANY member
-- reaches match-threshold AND max-sim vs one of these prototypes reaches
-- prototype-threshold (blocks single-linkage chain drift).
CREATE TABLE IF NOT EXISTS person_group_prototypes (
    group_id INTEGER NOT NULL REFERENCES person_groups(id) ON DELETE CASCADE,
    face_id  INTEGER NOT NULL REFERENCES faces(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, face_id)
);
