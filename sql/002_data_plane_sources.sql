-- ============================================================
-- OpenAlex 数据面 · sources 维表 (联调切片)
-- 契约全文: docs/002_sources_slice_contract.md
-- 白名单依据: 2026-06-26 快照实测 36 字段全集 (config/entities/sources.json)
-- 与控制面 openalex_sync 分库: CDC(二期)只订阅本库, binlog 策略独立
-- ============================================================

CREATE DATABASE IF NOT EXISTS openalex
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE openalex;

CREATE TABLE sources (
  -- 主键: OpenAlex 短号数字部分 (https://openalex.org/S15574646 → 15574646)
  id                BIGINT UNSIGNED NOT NULL,

  -- ---- 控制列 ----
  source_updated_at DATETIME(3) NOT NULL
                    COMMENT '上游 updated_date(UTC, 微秒截到毫秒); 最近一次引起投影变化的上游时间; 幂等守卫比较键',
  content_hash      BIGINT UNSIGNED NOT NULL
                    COMMENT '白名单投影 64 位指纹(契约 C4); 只由导入器计算, 永不从本表 payload 重算',
  first_imported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    COMMENT '本地首次落库时间',
  row_updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
                    COMMENT '本地最近实际写入时间, 仅运维观测',

  -- ---- 类型化业务列 (sources 无谓词列) ----
  display_name      VARCHAR(1024)   NULL,
  source_type       VARCHAR(32)     NULL COMMENT '上游 type: journal/conference/repository/...',
  issn_l            CHAR(9)         NULL,
  country_code      CHAR(2)         NULL,
  is_oa             TINYINT(1)      NULL,
  is_in_doaj        TINYINT(1)      NULL,
  works_count       INT UNSIGNED    NULL,
  cited_by_count    BIGINT UNSIGNED NULL,

  -- ---- JSON 载荷列 ----
  payload           JSON NOT NULL
                    COMMENT '白名单其余 24 字段原样透传(v1 不做载荷内部裁剪); MySQL 会重排 JSON 键序, 属预期',

  PRIMARY KEY (id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC
  COMMENT='OpenAlex sources 维表; 起步零二级索引, 索引按真实查询论证后再加';
