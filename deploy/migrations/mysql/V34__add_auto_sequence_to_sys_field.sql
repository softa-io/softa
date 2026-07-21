-- Add the `auto_sequence` column to the metadata catalog: sys_field plus its studio
-- mirror design_field. Matches the entity declarations (@Field(label = "Auto Sequence")
-- on SysField / DesignField) introduced together with the @Field(autoSequence = ...)
-- annotation attribute: the flag marks a STRING field as auto-filled from a sequence on
-- INSERT when the incoming value is blank (SequenceProcessor; the sequence itself is the
-- sys_sequence row named "<ModelName>.<fieldName>").
--
-- The catalog is itself annotation-managed, so a dev scanner-scope auto-applies the
-- column; this script converges existing environments (prod / scanner off). Fresh
-- installs never run it — deploy/{demo,mini}-app/init_mysql/1.Metadata.ddl.sql already
-- carries the column (design_field exists in demo-app only).
--
-- The UPDATEs are required alongside the DDL wherever the column was ALTERed in earlier
-- out-of-band deliveries WITHOUT a default: the annotation parser writes an explicit
-- false (never null), so a NULL row would diff against the parsed false and re-reconcile
-- (scanner) or report perpetual drift (read-only checker) until converged.
--
-- Ordering: run before booting the patched binary — SysJdbcLoader SELECTs the column
-- explicitly, and the strict scanner load fail-fasts on a missing column (it runs before
-- any auto-DDL could add it). Deployments without studio-starter have no design_* tables
-- — skip that section. Environments that already added the column via an earlier
-- out-of-band ALTER: skip that ADD COLUMN (MySQL has no IF NOT EXISTS for columns) but
-- DO run the paired UPDATE — those are exactly the environments at risk of NULL rows.

ALTER TABLE sys_field
    ADD COLUMN auto_sequence TINYINT(1) DEFAULT 0 COMMENT 'Auto Sequence;Auto-fill from a sequence on INSERT when blank'
    AFTER encrypted;
UPDATE sys_field SET auto_sequence = 0 WHERE auto_sequence IS NULL;

ALTER TABLE design_field
    ADD COLUMN auto_sequence TINYINT(1) DEFAULT 0 COMMENT 'Auto Sequence;Auto-fill from a sequence on INSERT when blank'
    AFTER encrypted;
UPDATE design_field SET auto_sequence = 0 WHERE auto_sequence IS NULL;
