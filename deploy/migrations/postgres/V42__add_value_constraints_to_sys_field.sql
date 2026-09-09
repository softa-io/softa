-- Add the value-domain columns to the metadata catalog: sys_field plus its studio mirror
-- design_field. Matches the entity declarations (min / max / pattern / constraint_message on
-- SysField / DesignField) introduced together with the @Field(min / max / pattern) annotation
-- attributes: a field's declared value domain, enforced by the field-processor pipeline that every
-- write path shares — create, update, batch, import, seed loading, flow write nodes. See
-- ValueConstraints, called from NumericProcessor and StringProcessor.
--
-- These four columns hold the DECLARATION, not an enforcement mechanism. No CHECK is rendered and
-- no business column changes type, so a bound is tightened by redeploying rather than by migrating
-- a table, and rows written before the bound existed stay valid — the behaviour a business rule
-- wants and a CHECK would not give.
--
-- The catalog is itself annotation-managed, so a non-empty scanner-scope auto-applies the sys_field
-- columns on boot; this script converges the environments where nothing auto-applies — an empty
-- scanner-scope (checker-only, the production shape), and design_* in every environment, which the
-- boot reconcile never covers.
--
-- NO BACKFILL UPDATE, unlike V34 and V41. FIELD_ATTRS is derived reflectively from SysField, so
-- these attributes join the cross-lane checksum the moment the columns exist — but they are String
-- attributes, and an undeclared domain parses to null on the code side while a freshly added column
-- is null on the DB side. The two agree from the first boot. (A boolean attribute needs the
-- backfill precisely because the parser writes an explicit false and null does not hash as false.)
--
-- Ordering: run before booting the patched binary. SysJdbcLoader SELECTs the columns explicitly;
-- the strict load fail-fasts on a missing column, and the lenient (checker) load degrades to an
-- empty catalog and reports everything as drift. Deployments without studio-starter have no
-- design_* tables — skip that section.
--
-- PostgreSQL variant: IF NOT EXISTS covers the re-run and the environment that already ALTERed out
-- of band; there is no column ordering and COMMENT is a separate statement.

ALTER TABLE sys_field ADD COLUMN IF NOT EXISTS min VARCHAR(40);
ALTER TABLE sys_field ADD COLUMN IF NOT EXISTS max VARCHAR(40);
ALTER TABLE sys_field ADD COLUMN IF NOT EXISTS pattern VARCHAR(256);
ALTER TABLE sys_field ADD COLUMN IF NOT EXISTS constraint_message VARCHAR(256);
COMMENT ON COLUMN sys_field.min IS 'Smallest accepted value, as a decimal literal; numeric fields only';
COMMENT ON COLUMN sys_field.max IS 'Largest accepted value, as a decimal literal; numeric fields only';
COMMENT ON COLUMN sys_field.pattern IS 'Regex the whole value must match; STRING and TEXT only';
COMMENT ON COLUMN sys_field.constraint_message IS 'Shown when a bound or the pattern rejects a value; its own i18n key';

ALTER TABLE design_field ADD COLUMN IF NOT EXISTS min VARCHAR(40);
ALTER TABLE design_field ADD COLUMN IF NOT EXISTS max VARCHAR(40);
ALTER TABLE design_field ADD COLUMN IF NOT EXISTS pattern VARCHAR(256);
ALTER TABLE design_field ADD COLUMN IF NOT EXISTS constraint_message VARCHAR(256);
COMMENT ON COLUMN design_field.min IS 'Smallest accepted value, as a decimal literal; numeric fields only';
COMMENT ON COLUMN design_field.max IS 'Largest accepted value, as a decimal literal; numeric fields only';
COMMENT ON COLUMN design_field.pattern IS 'Regex the whole value must match; STRING and TEXT only';
COMMENT ON COLUMN design_field.constraint_message IS 'Shown when a bound or the pattern rejects a value; its own i18n key';
