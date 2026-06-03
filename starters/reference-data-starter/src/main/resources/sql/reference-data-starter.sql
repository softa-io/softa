-- ============================================================
-- Reference Data Starter — DDL
-- Platform-level master tables for international SaaS:
--   country_region       — ISO 3166-1 alpha-2 countries (~249 rows when seeded)
--   currency             — ISO 4217 active currencies (~180 rows when seeded)
--   country_subdivision  — ISO 3166-2 subdivisions (empty until address feature lands)
--
-- All three are platform-level (no tenant_id column). Data is seeded via
-- metadata-starter's POST /SysPreData/loadPreSystemData using the JSON
-- files under src/main/resources/data-system/. There is NO auto-load on
-- startup — operators trigger loading explicitly.
-- ============================================================

CREATE TABLE IF NOT EXISTS country_region
(
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    code             VARCHAR(2)   NOT NULL COMMENT 'ISO 3166-1 alpha-2 (CN/US/TW/...); natural key',
    name             VARCHAR(100) NOT NULL COMMENT 'ISO 3166-1 English short name',
    alpha3_code      VARCHAR(3)   NOT NULL COMMENT 'ISO 3166-1 alpha-3 (CHN/USA/TWN)',
    dial_code        VARCHAR(8)   NOT NULL COMMENT 'ITU-T E.164 country dial code, no leading +',
    currency_code    VARCHAR(3)   NOT NULL COMMENT 'Default ISO 4217 currency; concept FK to currency.code',
    continent        VARCHAR(2)   NOT NULL COMMENT 'Continent code (AS/EU/AF/NA/SA/OC/AN)',
    eea              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'EEA / EU member (GDPR scope, VAT reverse charge)',
    has_subdivisions TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'True if country_subdivision rows exist for this country',
    created_time     DATETIME              COMMENT 'Created time',
    updated_time     DATETIME              COMMENT 'Updated time',
    created_id       BIGINT                COMMENT 'Created by user ID',
    created_by       VARCHAR(100)          COMMENT 'Created by username',
    updated_id       BIGINT                COMMENT 'Updated by user ID',
    updated_by       VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_code (code),
    INDEX idx_continent (continent),
    INDEX idx_currency_code (currency_code),
    INDEX idx_eea (eea)
) COMMENT = 'ISO 3166-1 alpha-2 country/region master, platform-level reference data';

CREATE TABLE IF NOT EXISTS currency
(
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    code           VARCHAR(3)   NOT NULL COMMENT 'ISO 4217 alpha-3 (USD/CNY/EUR/...); natural key',
    numeric_code   VARCHAR(3)   NOT NULL COMMENT 'ISO 4217 numeric, 3 digits with leading zero (840/156/048)',
    name           VARCHAR(100) NOT NULL COMMENT 'English name',
    symbol         VARCHAR(10)  NOT NULL COMMENT 'Unicode display symbol',
    decimal_places TINYINT      NOT NULL COMMENT 'ISO 4217 fraction digits (0/2/3/4); CRITICAL for monetary arithmetic',
    created_time   DATETIME              COMMENT 'Created time',
    updated_time   DATETIME              COMMENT 'Updated time',
    created_id     BIGINT                COMMENT 'Created by user ID',
    created_by     VARCHAR(100)          COMMENT 'Created by username',
    updated_id     BIGINT                COMMENT 'Updated by user ID',
    updated_by     VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_code (code)
) COMMENT = 'ISO 4217 currency master, platform-level reference data';

CREATE TABLE IF NOT EXISTS country_subdivision
(
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    country_code VARCHAR(2)   NOT NULL COMMENT 'ISO 3166-1 alpha-2; concept FK to country_region.code',
    code         VARCHAR(10)  NOT NULL COMMENT 'ISO 3166-2 full code (CN-31 / US-CA / JP-13); natural key',
    name         VARCHAR(100) NOT NULL COMMENT 'English name',
    parent_code  VARCHAR(10)           COMMENT 'Parent subdivision code (for hierarchical regions); null for top-level',
    type         VARCHAR(20)           COMMENT 'Subdivision type: province / state / prefecture / region / municipality / county',
    created_time DATETIME              COMMENT 'Created time',
    updated_time DATETIME              COMMENT 'Updated time',
    created_id   BIGINT                COMMENT 'Created by user ID',
    created_by   VARCHAR(100)          COMMENT 'Created by username',
    updated_id   BIGINT                COMMENT 'Updated by user ID',
    updated_by   VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_code (code),
    INDEX idx_country (country_code),
    INDEX idx_parent (parent_code)
) COMMENT = 'ISO 3166-2 country subdivisions. Schema ready; data populated when address/tax features land.';
