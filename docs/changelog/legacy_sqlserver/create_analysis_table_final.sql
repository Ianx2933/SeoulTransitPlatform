-- LEGACY - original SQL Server DDL for Analysis_Table_Final.
-- (레거시 - Analysis_Table_Final의 최초 SQL Server DDL)
--
-- Superseded by database/schema/01_curated_od.sql.
--
-- Note that every column here is VARCHAR. Four of them were later converted to
-- numeric types because the implicit CASTs were disabling indexes:
--
--   ALTER TABLE Analysis_Table_Final ALTER COLUMN 승차_정류장순번 INT;
--   ALTER TABLE Analysis_Table_Final ALTER COLUMN 하차_정류장순번 INT;
--   ALTER TABLE Analysis_Table_Final ALTER COLUMN 승객수 INT;
--   ALTER TABLE Analysis_Table_Final ALTER COLUMN 전환_노선ID BIGINT;
--
-- This file is the evidence for those types in the current schema; reading it
-- alone would suggest the columns should be text.
-- (이 파일만 보면 텍스트 컬럼으로 오해할 수 있어, 위 변환 이력을 함께 남깁니다.)

CREATE TABLE Analysis_Table_Final (
    기준일자            VARCHAR(20),
    노선명              NVARCHAR(100),
    전환_노선ID         VARCHAR(50),
    승차_정류장순번      VARCHAR(30),
    승차_정류장ARS      VARCHAR(30),
    승차_정류장표준코드  VARCHAR(50),
    승차_정류장명        NVARCHAR(200),
    하차_정류장순번      VARCHAR(30),
    하차_정류장ARS      VARCHAR(30),
    하차_정류장표준코드  VARCHAR(50),
    하차_정류장명        NVARCHAR(200),
    승객수              VARCHAR(20)
);
GO

BULK INSERT Analysis_Table_Final
FROM 'C:\data\merged_od_data.csv'
WITH (
    FIELDTERMINATOR = '|',
    ROWTERMINATOR   = '0x0a',
    FIRSTROW        = 2,
    CODEPAGE        = '65001'
);
GO