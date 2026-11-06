-- Education platform schema 4.2. FRESH ISOLATED DATABASE ONLY.
-- This is not a migration for an existing FCP database. Runtime users must not have DDL rights.
-- Native MySQL 8.4 acceptance is a separate, mandatory gate.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_bin;
SET SESSION time_zone = '+00:00';

CREATE TABLE `ed_core_tenant_binding` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `host_kind` VARCHAR(40) NOT NULL COMMENT '宿主适配器标识',
  `host_tenant_key` VARCHAR(100) NOT NULL COMMENT '宿主原始租户键',
  `timezone` VARCHAR(64) NOT NULL COMMENT '默认IANA时区',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：ACTIVE/FROZEN',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`),
  UNIQUE KEY `uq_1` (`host_kind`, `host_tenant_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_identity_person` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `display_name` VARCHAR(128) COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '姓名或获准显示名称；人类检索大小写/重音不敏感，绝不作身份键',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：ACTIVE/LEFT/RESTRICTED',
  `private_ref` VARCHAR(128) NULL COMMENT '受限隐私存储引用；不存身份证明文',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `display_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_identity_account_link` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `person_id` BIGINT UNSIGNED NOT NULL COMMENT '关联自然人',
  `host_user_key` VARCHAR(100) NOT NULL COMMENT '宿主账户原始ID',
  `valid_from` DATETIME(6) NOT NULL COMMENT '关系生效时刻',
  `valid_to` DATETIME(6) NULL COMMENT '关系结束，不含该时点',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `host_user_key`, `id`),
  KEY `ix_1` (`tenant_id`, `host_user_key`, `valid_from`),
  KEY `ix_2` (`tenant_id`, `person_id`),
  CONSTRAINT `ck_identity_account_link_1` CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_identity_external_map` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `person_id` BIGINT UNSIGNED NULL COMMENT '确认后绑定的人',
  `source_key` VARCHAR(64) NOT NULL COMMENT '经过授权的来源标识',
  `object_type` VARCHAR(40) NOT NULL COMMENT '外部对象类型',
  `external_key` VARCHAR(190) NOT NULL COMMENT '原始字符串标识，保留前导零',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：PENDING/CONFIRMED/CONFLICT',
  `approved_by` VARCHAR(100) NULL COMMENT '审核宿主主体ID',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `source_key`, `object_type`, `external_key`),
  KEY `ix_1` (`tenant_id`, `person_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_identity_relation` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `subject_person_id` BIGINT UNSIGNED NOT NULL COMMENT '学生',
  `related_person_id` BIGINT UNSIGNED NOT NULL COMMENT '监护人',
  `relation_kind` VARCHAR(40) NOT NULL COMMENT '关系类型',
  `valid_from` DATETIME(6) NOT NULL COMMENT '生效时刻',
  `valid_to` DATETIME(6) NULL COMMENT '结束时刻',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：PENDING/VERIFIED/REVOKED',
  `purpose_scope` VARCHAR(100) NOT NULL COMMENT '经批准的权限用途配置键',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `subject_person_id`, `valid_from`),
  KEY `ix_2` (`tenant_id`, `related_person_id`),
  CONSTRAINT `ck_identity_relation_1` CHECK (subject_person_id <> related_person_id),
  CONSTRAINT `ck_identity_relation_2` CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_education_org_unit` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `parent_id` BIGINT UNSIGNED NULL COMMENT '父组织',
  `org_code` VARCHAR(64) NOT NULL COMMENT '租户内稳定编码',
  `org_kind` VARCHAR(32) NOT NULL COMMENT 'SCHOOL/CAMPUS/DEPARTMENT',
  `name` VARCHAR(190) COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '组织名称；人类检索大小写/重音不敏感，绝不作身份键',
  `valid_from` DATETIME(6) NOT NULL COMMENT '有效起点',
  `valid_to` DATETIME(6) NULL COMMENT '撤并时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `org_code`),
  KEY `ix_1` (`tenant_id`, `parent_id`),
  CONSTRAINT `ck_education_org_unit_1` CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_education_term` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `school_id` BIGINT UNSIGNED NOT NULL COMMENT '所属学校',
  `term_code` VARCHAR(64) NOT NULL COMMENT '校内学期编码',
  `starts_on` DATE NOT NULL COMMENT '业务开始日期',
  `ends_on` DATE NOT NULL COMMENT '业务结束日期，含当天',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：DRAFT/OPEN/FROZEN/ARCHIVED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `school_id`, `term_code`),
  UNIQUE KEY `uq_2` (`tenant_id`, `school_id`, `id`),
  CONSTRAINT `ck_education_term_1` CHECK (ends_on >= starts_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_education_class` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `school_id` BIGINT UNSIGNED NOT NULL COMMENT '所属学校',
  `term_id` BIGINT UNSIGNED NOT NULL COMMENT '学期',
  `class_code` VARCHAR(64) NOT NULL COMMENT '校内学期班级编码',
  `name` VARCHAR(128) COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '展示名称；人类检索大小写/重音不敏感，绝不作身份键',
  `grade_code` VARCHAR(40) NOT NULL COMMENT '学段年级口径',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `school_id`, `term_id`, `class_code`),
  UNIQUE KEY `uq_2` (`tenant_id`, `school_id`, `id`),
  KEY `ix_1` (`tenant_id`, `term_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_education_enrollment` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `person_id` BIGINT UNSIGNED NOT NULL COMMENT '自然人，跨领域逻辑引用',
  `school_id` BIGINT UNSIGNED NOT NULL COMMENT '学校',
  `student_code` VARCHAR(64) NOT NULL COMMENT '学号字符串，保留前导零',
  `admission_key` VARCHAR(64) NOT NULL COMMENT '经校方确定的学籍/入学周期键',
  `valid_from` DATETIME(6) NOT NULL COMMENT '在读生效',
  `valid_to` DATETIME(6) NULL COMMENT '结束',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：ACTIVE/TRANSFERRED/GRADUATED/SUSPENDED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `school_id`, `admission_key`, `student_code`),
  UNIQUE KEY `uq_2` (`tenant_id`, `school_id`, `id`),
  KEY `ix_1` (`tenant_id`, `person_id`, `valid_from`),
  CONSTRAINT `ck_education_enrollment_1` CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_education_membership` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `enrollment_id` BIGINT UNSIGNED NOT NULL COMMENT '学籍关系',
  `class_id` BIGINT UNSIGNED NOT NULL COMMENT '所属班级',
  `valid_from` DATETIME(6) NOT NULL COMMENT '生效',
  `valid_to` DATETIME(6) NULL COMMENT '结束',
  `change_reason` VARCHAR(190) NOT NULL COMMENT '转班/更正原因',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `school_id` BIGINT UNSIGNED NOT NULL COMMENT '普通入班必须同校；跨校项目走独立关系模型',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `enrollment_id`, `valid_from`),
  KEY `ix_2` (`tenant_id`, `class_id`, `valid_from`),
  KEY `ix_3` (`tenant_id`, `school_id`, `enrollment_id`),
  KEY `ix_4` (`tenant_id`, `school_id`, `class_id`),
  CONSTRAINT `ck_education_membership_1` CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_education_assignment` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `person_id` BIGINT UNSIGNED NOT NULL COMMENT '教师自然人逻辑引用',
  `class_id` BIGINT UNSIGNED NOT NULL COMMENT '班级',
  `subject_code` VARCHAR(64) NOT NULL COMMENT '学科编码',
  `role_code` VARCHAR(40) NOT NULL COMMENT 'TEACHER/SUBSTITUTE/HEAD',
  `valid_from` DATETIME(6) NOT NULL COMMENT '授权开始',
  `valid_to` DATETIME(6) NULL COMMENT '授权结束',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `person_id`, `valid_from`),
  KEY `ix_2` (`tenant_id`, `class_id`),
  CONSTRAINT `ck_education_assignment_1` CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_access_epoch` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `subject_key` VARCHAR(100) NOT NULL COMMENT '宿主主体',
  `epoch` BIGINT UNSIGNED NOT NULL COMMENT '撤权/角色变化版本',
  `changed_at` DATETIME(6) NOT NULL COMMENT '最后权限变化时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前主体可用',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `subject_key`),
  CONSTRAINT `ck_core_access_epoch_1` CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_evidence_object` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `object_key` VARCHAR(190) NOT NULL COMMENT '对象存储内部键；不能暴露为永久公网地址',
  `sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内容校验值',
  `byte_size` BIGINT UNSIGNED NOT NULL COMMENT '字节数',
  `media_type` VARCHAR(100) NOT NULL COMMENT '内容类型',
  `classification` VARCHAR(32) NOT NULL COMMENT '一般/敏感/严格受限',
  `retention_policy` VARCHAR(100) NOT NULL COMMENT '保留规则版本键',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：QUARANTINED/READY/BLOCKED/PURGED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `object_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_evidence_binding` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `evidence_id` BIGINT UNSIGNED NOT NULL COMMENT '附件',
  `owner_domain` VARCHAR(64) NOT NULL COMMENT '事实所属域',
  `object_type` VARCHAR(64) NOT NULL COMMENT '对象类型',
  `object_id` VARCHAR(190) NOT NULL COMMENT '领域对象ID字符串；对象引用协议ref-v1，最多190字符',
  `purpose` VARCHAR(128) NOT NULL COMMENT '收集与复用目的',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `evidence_id`, `owner_domain`, `object_type`, `object_id`, `purpose`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_command_request` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `actor_key` VARCHAR(100) NOT NULL COMMENT '可信主体',
  `effect_namespace` VARCHAR(100) NOT NULL COMMENT '稳定正式效果命名空间，不包含代码或协议版本',
  `intent_id` VARCHAR(100) NOT NULL COMMENT '业务意图ID，重试/跨版本/离线重放保持相同',
  `fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '含作用域和参数的规范化摘要',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：ACCEPTED/COMMITTED/REJECTED',
  `result_json` JSON NULL COMMENT '最小必要响应，不存令牌',
  `expires_at` DATETIME(6) NULL COMMENT '保留策略约定的过期时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `contract_version` VARCHAR(40) NOT NULL COMMENT '传输契约版本，仅诊断',
  `normalization_version` VARCHAR(40) NOT NULL COMMENT '规范意图格式；同一命名空间必须兼容',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `effect_namespace`, `intent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_audit_event` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `actor_key` VARCHAR(100) NOT NULL COMMENT '可信主体',
  `action_key` VARCHAR(100) NOT NULL COMMENT '行为类型',
  `owner_domain` VARCHAR(64) NOT NULL COMMENT '对象领域',
  `object_id` VARCHAR(190) NOT NULL COMMENT '对象标识；对象引用协议ref-v1，最多190字符',
  `trace_id` VARCHAR(64) NULL COMMENT '可空的诊断Trace关联；正式审计不能依赖采样Trace存续',
  `changes_json` JSON NOT NULL COMMENT '受控字段变化摘要；禁止原样写入敏感请求',
  `occurred_at` DATETIME(6) NOT NULL COMMENT '事件发生UTC时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `object_type` VARCHAR(64) NOT NULL COMMENT 'ref-v1实体类型',
  `operation_id` VARCHAR(100) NOT NULL COMMENT '稳定业务操作ID',
  `request_attempt_id` VARCHAR(100) NOT NULL COMMENT '本次请求尝试',
  `result` VARCHAR(16) NOT NULL COMMENT '正式事务只记录COMMITTED',
  `manifest_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际执行发布清单摘要',
  `config_revision` VARCHAR(64) NOT NULL COMMENT '实际配置修订',
  `rule_revision` VARCHAR(64) NOT NULL COMMENT '实际规则修订',
  `audit_schema_version` VARCHAR(16) NOT NULL DEFAULT 'audit-v2' COMMENT '审计事件结构版本，当前audit-v2',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `owner_domain`, `object_id`, `occurred_at`),
  KEY `ix_2` (`tenant_id`, `operation_id`, `occurred_at`),
  KEY `ix_3` (`tenant_id`, `trace_id`),
  CONSTRAINT `ck_core_audit_event_1` CHECK (result = 'COMMITTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_outbox` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `event_id` VARCHAR(64) NOT NULL COMMENT '全局事件标识',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
  `aggregate_id` VARCHAR(190) NOT NULL COMMENT '聚合标识；对象引用协议ref-v1，最多190字符',
  `aggregate_seq` BIGINT UNSIGNED NOT NULL COMMENT '聚合事件顺序',
  `event_type` VARCHAR(100) NOT NULL COMMENT '含主版本的事件类型',
  `payload` JSON NOT NULL COMMENT '最小事件数据，禁止默认携带全档案',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：NEW/LEASED/SENT/DEAD',
  `available_at` DATETIME(6) NOT NULL COMMENT '下一可处理时刻',
  `lease_until` DATETIME(6) NULL COMMENT '租约到期',
  `attempts` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重试次数',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `event_id`),
  UNIQUE KEY `uq_2` (`tenant_id`, `aggregate_type`, `aggregate_id`, `aggregate_seq`),
  KEY `ix_1` (`status`, `available_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_inbox` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `effect_namespace` VARCHAR(100) NOT NULL COMMENT '稳定效果类别；重建投影可使用隔离generation命名空间',
  `event_id` VARCHAR(64) NOT NULL COMMENT '事件标识',
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '载荷摘要',
  `processed_at` DATETIME(6) NOT NULL COMMENT '本地提交时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `effect_key` VARCHAR(128) NOT NULL COMMENT '业务效果键；事件重新封装也保持不变',
  `implementation_version` VARCHAR(40) NOT NULL COMMENT '消费者实现版本，不能进入正式去重键',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `effect_namespace`, `effect_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_config_release` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `config_kind` VARCHAR(40) NOT NULL COMMENT 'RULE/UI/FORM/REPORT',
  `config_key` VARCHAR(100) NOT NULL COMMENT '配置命名空间',
  `version_no` INT UNSIGNED NOT NULL COMMENT '显式版本',
  `body_json` JSON NOT NULL COMMENT '声明式配置；不可存任意SQL/脚本',
  `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化内容摘要',
  `approved_by` VARCHAR(100) NOT NULL COMMENT '发布审批人',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `config_kind`, `config_key`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_config_binding` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `scope_kind` VARCHAR(40) NOT NULL COMMENT 'SCHOOL/ROLE/ENTRY',
  `scope_key` VARCHAR(100) NOT NULL COMMENT '作用域键',
  `slot_key` VARCHAR(100) NOT NULL COMMENT '能力插槽/界面位置',
  `release_id` BIGINT UNSIGNED NOT NULL COMMENT '当前选用版本',
  `effective_from` DATETIME(6) NOT NULL COMMENT '生效时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `scope_kind`, `scope_key`, `slot_key`),
  KEY `ix_1` (`tenant_id`, `release_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_config_extension_schema` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `owner_domain` VARCHAR(64) NOT NULL COMMENT '归属业务域',
  `entity_kind` VARCHAR(64) NOT NULL COMMENT '实体类型',
  `version_no` INT UNSIGNED NOT NULL COMMENT '模式版本',
  `schema_json` JSON NOT NULL COMMENT 'JSON Schema子集及分类标签',
  `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '摘要',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `scope_kind` VARCHAR(16) NOT NULL COMMENT 'TENANT或SCHOOL',
  `scope_key` VARCHAR(100) NOT NULL COMMENT 'TENANT时可信tenant_id；SCHOOL时校ID十进制字符串，scope-v1解析',
  `schema_key` VARCHAR(64) NOT NULL COMMENT '稳定模式族标识',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `owner_domain`, `entity_kind`, `scope_kind`, `scope_key`, `schema_key`, `version_no`),
  CONSTRAINT `ck_config_extension_schema_1` CHECK (scope_kind IN ('TENANT','SCHOOL')),
  CONSTRAINT `ck_config_extension_schema_2` CHECK (version_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_roster_attendance` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `enrollment_id` BIGINT UNSIGNED NOT NULL COMMENT '入学关系逻辑引用',
  `class_id` BIGINT UNSIGNED NOT NULL COMMENT '发生时班级逻辑引用',
  `occurred_on` DATE NOT NULL COMMENT '校方业务时区的考勤日期',
  `session_key` VARCHAR(64) NOT NULL COMMENT '默认DAY；未来上午/课程/节次',
  `attendance_status` VARCHAR(32) NOT NULL COMMENT 'PRESENT/LATE/ABSENT/LEAVE/UNVERIFIED',
  `source_key` VARCHAR(190) NOT NULL COMMENT '来源及外部键摘要',
  `recorded_at` DATETIME(6) NOT NULL COMMENT '记录时刻',
  `extensions` JSON NULL COMMENT '有版本约束的可选属性',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `extensions_schema_id` BIGINT UNSIGNED NULL COMMENT 'extensions精确绑定的已发布模式ID',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `enrollment_id`, `occurred_on`, `session_key`),
  KEY `ix_1` (`tenant_id`, `class_id`, `occurred_on`, `id`),
  CONSTRAINT `ck_roster_attendance_1` CHECK ((extensions IS NULL AND extensions_schema_id IS NULL) OR (extensions IS NOT NULL AND extensions_schema_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_eval_rubric` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `rubric_key` VARCHAR(100) NOT NULL COMMENT '量规标识',
  `version_no` INT UNSIGNED NOT NULL COMMENT '版本',
  `school_id` BIGINT UNSIGNED NOT NULL COMMENT '已解析的学校组织ID，校本量规独立版本',
  `rule_json` JSON NOT NULL COMMENT '受控表达式、观察状态和分值含义',
  `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '摘要',
  `approved_by` VARCHAR(100) NOT NULL COMMENT '校方审批主体',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `school_id`, `rubric_key`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_eval_indicator` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `rubric_id` BIGINT UNSIGNED NOT NULL COMMENT '量规版本',
  `indicator_code` VARCHAR(100) NOT NULL COMMENT '版本内指标编码',
  `parent_code` VARCHAR(100) NULL COMMENT '父指标编码',
  `label` VARCHAR(190) COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '显示名称；人类检索大小写/重音不敏感，绝不作身份键',
  `value_kind` VARCHAR(40) NOT NULL COMMENT 'DECIMAL/CATEGORY/OBSERVATION',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `rubric_id`, `indicator_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_eval_observation` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `subject_kind` VARCHAR(32) NOT NULL COMMENT 'PERSON/CLASS，不得互相混算',
  `subject_id` BIGINT UNSIGNED NOT NULL COMMENT 'subject_kind决定PERSON或CLASS；组合由subject-v1解析',
  `class_id` BIGINT UNSIGNED NULL COMMENT '发生时学期班级实例ID；不可由标签反查；无班级的合法记录为空，绝不伪造班级',
  `actor_key` VARCHAR(100) NOT NULL COMMENT '教师/观察者',
  `occurred_at` DATETIME(6) NOT NULL COMMENT '发生时刻',
  `observation_state` VARCHAR(32) NOT NULL COMMENT 'OBSERVED/NOT_OBSERVED/ABSENT/EXEMPT',
  `summary` TEXT NOT NULL COMMENT '最小事实描述，个人信息权限适用',
  `extensions` JSON NULL COMMENT '扩展字段值及对应schema版本',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `class_label_snapshot` VARCHAR(190) NULL COMMENT '发生时展示名，不能作为连接键',
  `extensions_schema_id` BIGINT UNSIGNED NULL COMMENT 'extensions精确绑定的已发布模式ID',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `subject_kind`, `subject_id`, `occurred_at`, `id`),
  CONSTRAINT `ck_eval_observation_1` CHECK (subject_kind IN ('PERSON','CLASS')),
  CONSTRAINT `ck_eval_observation_2` CHECK ((extensions IS NULL AND extensions_schema_id IS NULL) OR (extensions IS NOT NULL AND extensions_schema_id IS NOT NULL)),
  CONSTRAINT `ck_eval_observation_3` CHECK (subject_kind <> 'CLASS' OR (class_id IS NOT NULL AND class_id = subject_id))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_eval_judgement` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `observation_id` BIGINT UNSIGNED NOT NULL COMMENT '对应观察',
  `indicator_id` BIGINT UNSIGNED NOT NULL COMMENT '量规版本内指标',
  `score` DECIMAL(12,4) NULL COMMENT '仅适用分值型评价，可空',
  `category` VARCHAR(64) NULL COMMENT '类别型结果',
  `confirmed_by` VARCHAR(100) NOT NULL COMMENT '有权确认主体',
  `confirmed_at` DATETIME(6) NOT NULL COMMENT '确认时间',
  `supersedes_id` BIGINT UNSIGNED NULL COMMENT '更正所替代的判定',
  `correction_reason` VARCHAR(190) NULL COMMENT '更正说明',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `series_id` BIGINT UNSIGNED NOT NULL COMMENT '所属判定系列',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '系列内从1递增；在系列锁内分配',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `series_id`, `id`),
  UNIQUE KEY `uq_2` (`tenant_id`, `series_id`, `revision_no`),
  UNIQUE KEY `uq_3` (`tenant_id`, `series_id`, `supersedes_id`),
  KEY `ix_1` (`tenant_id`, `observation_id`, `id`),
  KEY `ix_2` (`tenant_id`, `indicator_id`),
  KEY `ix_3` (`tenant_id`, `supersedes_id`),
  KEY `ix_4` (`tenant_id`, `series_id`, `observation_id`, `indicator_id`),
  CONSTRAINT `ck_eval_judgement_1` CHECK ((revision_no = 1 AND supersedes_id IS NULL) OR (revision_no > 1 AND supersedes_id IS NOT NULL)),
  CONSTRAINT `ck_eval_judgement_2` CHECK (supersedes_id IS NULL OR supersedes_id <> id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_duty_issue` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `class_id` BIGINT UNSIGNED NOT NULL COMMENT '发生时班级实例ID',
  `issue_kind` VARCHAR(64) NOT NULL COMMENT '问题类别',
  `reporter_key` VARCHAR(100) NOT NULL COMMENT '登记人',
  `occurred_at` DATETIME(6) NOT NULL COMMENT '发生时间',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：PENDING/ASSIGNED/RESOLVED/REVIEWED/DISPUTED/CLOSED',
  `duplicate_of` BIGINT UNSIGNED NULL COMMENT '重复问题关联',
  `summary` VARCHAR(512) NOT NULL COMMENT '最小描述',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `class_label_snapshot` VARCHAR(190) NOT NULL COMMENT '发生时班名',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `class_id`, `status`, `occurred_at`),
  KEY `ix_2` (`tenant_id`, `duplicate_of`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_duty_action` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `issue_id` BIGINT UNSIGNED NOT NULL COMMENT '值周事项',
  `action_type` VARCHAR(40) NOT NULL COMMENT 'ASSIGN/REMEDY/REVIEW/APPEAL/CLOSE',
  `actor_key` VARCHAR(100) NOT NULL COMMENT '责任主体',
  `result` VARCHAR(512) NOT NULL COMMENT '处理结论',
  `acted_at` DATETIME(6) NOT NULL COMMENT '执行时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `issue_id`, `acted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_workflow_definition` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `workflow_key` VARCHAR(100) NOT NULL COMMENT '流程编码',
  `version_no` INT UNSIGNED NOT NULL COMMENT '版本',
  `definition_json` JSON NOT NULL COMMENT '受控状态机，禁止任意脚本',
  `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '摘要',
  `approved_by` VARCHAR(100) NOT NULL COMMENT '审批主体',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `workflow_key`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_workflow_case` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `definition_id` BIGINT UNSIGNED NOT NULL COMMENT '固定版本',
  `owner_domain` VARCHAR(64) NOT NULL COMMENT '业务权威域',
  `business_key` VARCHAR(100) NOT NULL COMMENT '业务对象或事项ID',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：OPEN/WAITING/COMPLETED/CANCELLED/UNCERTAIN',
  `current_state` VARCHAR(64) NOT NULL COMMENT '当前合法状态',
  `context_json` JSON NOT NULL COMMENT '最小上下文；业务正式数据仍在领域表',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `owner_domain`, `business_key`),
  KEY `ix_2` (`tenant_id`, `definition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_workflow_task` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `case_id` BIGINT UNSIGNED NOT NULL COMMENT '所属事项',
  `step_key` VARCHAR(100) NOT NULL COMMENT '定义中的步骤键',
  `assignee_key` VARCHAR(100) NOT NULL COMMENT '有权办理主体/组键',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：READY/CLAIMED/DONE/REJECTED/CANCELLED',
  `due_at` DATETIME(6) NULL COMMENT '截止时间',
  `lease_until` DATETIME(6) NULL COMMENT '办理租约',
  `fence_epoch` BIGINT UNSIGNED NOT NULL COMMENT '阻止旧任务写回的栅栏版本',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `claim_token` VARCHAR(64) NULL COMMENT '每次领取生成的新令牌，不能仅按worker名认领',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `case_id`, `id`),
  KEY `ix_1` (`tenant_id`, `assignee_key`, `status`, `due_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_workflow_action` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `case_id` BIGINT UNSIGNED NOT NULL COMMENT '事项',
  `task_id` BIGINT UNSIGNED NULL COMMENT '任务',
  `actor_key` VARCHAR(100) NOT NULL COMMENT '办理主体',
  `action_key` VARCHAR(64) NOT NULL COMMENT '经过授权的动作',
  `from_state` VARCHAR(64) NOT NULL COMMENT '旧状态',
  `to_state` VARCHAR(64) NOT NULL COMMENT '新状态',
  `acted_at` DATETIME(6) NOT NULL COMMENT '发生时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `actor_kind` VARCHAR(16) NOT NULL COMMENT 'USER或SYSTEM',
  `trigger_key` VARCHAR(190) NOT NULL COMMENT '定时器/回执/规则触发ID，幂等来源',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `case_id`, `acted_at`),
  KEY `ix_2` (`tenant_id`, `task_id`),
  KEY `ix_3` (`tenant_id`, `case_id`, `task_id`),
  CONSTRAINT `ck_workflow_action_1` CHECK (actor_kind IN ('USER','SYSTEM')),
  CONSTRAINT `ck_workflow_action_2` CHECK (actor_kind <> 'USER' OR task_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_integration_source` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `source_key` VARCHAR(64) NOT NULL COMMENT '来源编码',
  `plugin_key` VARCHAR(100) NOT NULL COMMENT '已安装插件标识',
  `secret_ref` VARCHAR(190) NOT NULL COMMENT '密钥管理引用，绝不存明文密码',
  `endpoint_policy_key` VARCHAR(100) NOT NULL COMMENT '经过部署审核的地址白名单键',
  `mapping_version` VARCHAR(64) NOT NULL COMMENT '输入映射契约版本',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：ACTIVE/PAUSED/REVOKED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `source_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_integration_import_batch` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `source_id` BIGINT UNSIGNED NOT NULL COMMENT '来源',
  `external_batch_key` VARCHAR(100) NOT NULL COMMENT '来源批次键',
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '输入摘要',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：STAGED/INVALID/READY/COMMITTING/COMMITTED/UNCERTAIN',
  `expected_rows` INT UNSIGNED NOT NULL COMMENT '应处理行数',
  `committed_rows` INT UNSIGNED NOT NULL COMMENT '已确认提交行数',
  `checkpoint` VARCHAR(190) NULL COMMENT '恢复游标',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `source_id`, `external_batch_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_integration_import_error` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '批次',
  `row_no` INT UNSIGNED NOT NULL COMMENT '输入行号',
  `error_code` VARCHAR(64) NOT NULL COMMENT '结构化错误码',
  `field_key` VARCHAR(100) NOT NULL COMMENT '字段',
  `redacted_detail` VARCHAR(512) NOT NULL COMMENT '脱敏错误说明',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `batch_id`, `row_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_integration_external_action` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `operation_key` VARCHAR(128) NOT NULL COMMENT '含目标系统的幂等操作键',
  `owner_domain` VARCHAR(64) NOT NULL COMMENT '发起业务域',
  `business_key` VARCHAR(100) NOT NULL COMMENT '来源业务ID',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：PLANNED/SENT/ACKNOWLEDGED/UNCERTAIN/COMPENSATED/FAILED',
  `minimal_request` JSON NOT NULL COMMENT '最小外部动作参数',
  `external_receipt` VARCHAR(190) NULL COMMENT '外部回执键',
  `next_reconcile_at` DATETIME(6) NULL COMMENT '下一核对时刻',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `operation_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_growth_report` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `enrollment_id` BIGINT UNSIGNED NOT NULL COMMENT '本报告对应的学籍身份',
  `term_id` BIGINT UNSIGNED NOT NULL COMMENT '报告学期实例',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '同报告修订序号',
  `source_snapshot_json` JSON NOT NULL COMMENT '完整来源清单/快照令牌协议snapshot-v1；各域单独游标，不用MAX(id)证明完整性',
  `rule_release_key` VARCHAR(128) NOT NULL COMMENT '规则版本',
  `template_release_key` VARCHAR(128) NOT NULL COMMENT '模板版本',
  `model_release_key` VARCHAR(128) NULL COMMENT '可空的AI版本',
  `evidence_id` BIGINT UNSIGNED NOT NULL COMMENT '成品文档逻辑引用',
  `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '报告摘要',
  `approved_by` VARCHAR(100) NOT NULL COMMENT '审定人',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `student_label_snapshot` VARCHAR(190) NOT NULL COMMENT '冻结报告显示名，敏感',
  `term_label_snapshot` VARCHAR(190) NOT NULL COMMENT '冻结学期显示名',
  `source_snapshot_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化清单SHA256',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `enrollment_id`, `term_id`, `revision_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_growth_report_item` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `report_id` BIGINT UNSIGNED NOT NULL COMMENT '报告',
  `source_domain` VARCHAR(64) NOT NULL COMMENT '事实归属',
  `source_type` VARCHAR(64) NOT NULL COMMENT '事实类型',
  `source_id` VARCHAR(190) NOT NULL COMMENT '事实标识；对象引用协议ref-v1，最多190字符',
  `source_version` VARCHAR(64) NOT NULL COMMENT '事实版本',
  `paragraph_key` VARCHAR(100) NOT NULL COMMENT '报告段落或字段',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `report_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_reward_account` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `person_id` BIGINT UNSIGNED NOT NULL COMMENT '内部人员ID',
  `currency_key` VARCHAR(64) NOT NULL COMMENT '学校定义的积分种类',
  `balance` DECIMAL(16,4) NOT NULL COMMENT '缓存余额，须能由账本重建',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：ACTIVE/FROZEN/CLOSED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `unit_scale` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '账户最小单位小数位，创建后不可变，0至4',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `person_id`, `currency_key`),
  CONSTRAINT `ck_reward_account_1` CHECK (unit_scale <= 4),
  CONSTRAINT `ck_reward_account_2` CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_reward_entry` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '账户',
  `business_key` VARCHAR(128) NOT NULL COMMENT '业务唯一键',
  `amount` DECIMAL(16,4) NOT NULL COMMENT '正负变化',
  `reverses_entry_id` BIGINT UNSIGNED NULL COMMENT '原流水',
  `reason` VARCHAR(190) NOT NULL COMMENT '原因',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `entry_type` VARCHAR(16) NOT NULL COMMENT 'AWARD/SPEND/EXPIRE/REFUND/ADJUSTMENT/REVERSAL',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `account_id`, `business_key`),
  UNIQUE KEY `uq_2` (`tenant_id`, `account_id`, `id`),
  KEY `ix_1` (`tenant_id`, `reverses_entry_id`),
  KEY `ix_2` (`tenant_id`, `account_id`, `reverses_entry_id`),
  CONSTRAINT `ck_reward_entry_1` CHECK (entry_type IN ('AWARD','SPEND','EXPIRE','REFUND','ADJUSTMENT','REVERSAL')),
  CONSTRAINT `ck_reward_entry_2` CHECK (amount <> 0),
  CONSTRAINT `ck_reward_entry_3` CHECK ((entry_type IN ('AWARD','REFUND') AND amount > 0) OR (entry_type IN ('SPEND','EXPIRE') AND amount < 0) OR entry_type IN ('ADJUSTMENT','REVERSAL')),
  CONSTRAINT `ck_reward_entry_4` CHECK ((entry_type = 'REVERSAL' AND reverses_entry_id IS NOT NULL) OR (entry_type <> 'REVERSAL' AND reverses_entry_id IS NULL)),
  CONSTRAINT `ck_reward_entry_5` CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_migration_job` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `job_key` VARCHAR(100) NOT NULL COMMENT '任务唯一键',
  `owner_domain` VARCHAR(64) NOT NULL COMMENT '领域',
  `plan_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '获批计划摘要',
  `status` VARCHAR(32) NOT NULL COMMENT '状态取值及转换由领域契约验证：PLANNED/RUNNING/PAUSED/FAILED/VERIFIED/SWITCHED',
  `checkpoint` VARCHAR(190) NULL COMMENT '已提交稳定游标',
  `source_version` BIGINT UNSIGNED NOT NULL COMMENT '源版本水位',
  `verified_rows` BIGINT UNSIGNED NOT NULL COMMENT '已核对行数',
  `checks_json` JSON NOT NULL COMMENT '数量/摘要/差异口径',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `job_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_legacy_map` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `legacy_kind` VARCHAR(32) NOT NULL COMMENT '旧kind原样保存',
  `legacy_record_id` VARCHAR(64) NOT NULL COMMENT '旧record_id原样保存',
  `legacy_item_key` VARCHAR(190) NOT NULL COMMENT '整包内稳定记录键，正式探查后定义',
  `legacy_version` BIGINT UNSIGNED NOT NULL COMMENT '源版本',
  `target_domain` VARCHAR(64) NOT NULL COMMENT '新领域',
  `target_type` VARCHAR(64) NOT NULL COMMENT '新类型',
  `target_id` VARCHAR(190) NOT NULL COMMENT '新ID字符串；对象引用协议ref-v1，最多190字符',
  `item_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化单项摘要',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `legacy_kind`, `legacy_record_id`, `legacy_item_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_release_binding` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `deployment_key` VARCHAR(100) NOT NULL COMMENT '部署实例/学校组键',
  `manifest_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全组件发布清单摘要',
  `source_commit` VARCHAR(64) NOT NULL COMMENT '完整Git SHA',
  `versions_json` JSON NOT NULL COMMENT '宿主/插件/配置/界面/数据模式锁定组合',
  `status` VARCHAR(32) NOT NULL COMMENT 'REGISTERED/RETIRED；生效权威在current指针',
  `activated_at` DATETIME(6) NULL COMMENT '生效时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `deployment_key`, `id`),
  KEY `ix_1` (`tenant_id`, `deployment_key`, `status`),
  CONSTRAINT `ck_ops_release_binding_1` CHECK (status IN ('REGISTERED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_reporting_timeline` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `person_id` BIGINT UNSIGNED NOT NULL COMMENT '受控投影对象人员ID',
  `class_id` BIGINT UNSIGNED NULL COMMENT '来源发生时班级实例；未关联班级的记录不通过教师班级查询；无班级的合法记录为空，绝不伪造班级',
  `source_domain` VARCHAR(64) NOT NULL COMMENT '来源域',
  `source_id` VARCHAR(190) NOT NULL COMMENT '来源事实ID；对象引用协议ref-v1，最多190字符',
  `source_version` BIGINT UNSIGNED NOT NULL COMMENT '源版本',
  `occurred_at` DATETIME(6) NOT NULL COMMENT '发生时刻',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型',
  `redacted_summary` VARCHAR(512) COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '最小展示文案；人类检索大小写/重音不敏感，绝不作身份键',
  `purpose_key` VARCHAR(64) NOT NULL COMMENT '允许的数据集用途',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新必须比较旧值',
  `source_type` VARCHAR(64) NOT NULL COMMENT '源领域内实体类型',
  `projection_kind` VARCHAR(64) NOT NULL DEFAULT 'timeline-v1' COMMENT '投影模式',
  `is_visible` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '撤回后保留去重墓碑，查询过滤',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `source_domain`, `source_type`, `source_id`, `projection_kind`, `purpose_key`, `person_id`),
  KEY `ix_1` (`tenant_id`, `class_id`, `occurred_at`, `id`),
  KEY `ix_2` (`tenant_id`, `person_id`, `occurred_at`, `id`),
  CONSTRAINT `ck_reporting_timeline_1` CHECK (is_visible IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_reporting_query_release` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信宿主提供的不透明租户作用域；不截断或重算旧scope_id',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '宿主统一ID分配器生成；API以字符串传输',
  `query_key` VARCHAR(100) NOT NULL COMMENT '接口或查询标识',
  `version_no` INT UNSIGNED NOT NULL COMMENT '版本',
  `code_artifact_ref` VARCHAR(190) NOT NULL COMMENT '已审查SQL资源/编译DSL的代码位置',
  `code_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已审代码摘要',
  `parameter_schema` JSON NOT NULL COMMENT '参数类型及上限',
  `output_schema` JSON NOT NULL COMMENT '可见列与脱敏规则',
  `required_capability` VARCHAR(100) NOT NULL COMMENT '必需授权',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库时刻',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `query_key`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_eval_judgement_series` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '统一ID；API字符串',
  `observation_id` BIGINT UNSIGNED NOT NULL COMMENT '观察事实',
  `indicator_id` BIGINT UNSIGNED NOT NULL COMMENT '固定量规中的指标',
  `judge_key` VARCHAR(100) NOT NULL COMMENT '独立评价主体',
  `purpose_key` VARCHAR(64) NOT NULL COMMENT '正式用途',
  `current_judgement_id` BIGINT UNSIGNED NULL COMMENT '当前已确认修订；只允许在创建事务暂空',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'CAS修订号',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `observation_id`, `indicator_id`, `judge_key`, `purpose_key`),
  UNIQUE KEY `uq_2` (`tenant_id`, `id`, `observation_id`, `indicator_id`),
  KEY `ix_1` (`tenant_id`, `indicator_id`),
  KEY `ix_2` (`tenant_id`, `id`, `current_judgement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_security_event` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '统一ID；API字符串',
  `actor_key` VARCHAR(100) NOT NULL COMMENT '可信主体或anonymous',
  `operation_id` VARCHAR(100) NULL COMMENT '业务关联，可能为空',
  `request_attempt_id` VARCHAR(100) NOT NULL COMMENT '请求尝试',
  `event_code` VARCHAR(64) NOT NULL COMMENT '白名单稳定错误码',
  `outcome` VARCHAR(16) NOT NULL COMMENT 'DENIED/FAILED/UNKNOWN',
  `safe_context_json` JSON NOT NULL COMMENT '白名单结构，不含任意异常正文',
  `occurred_at` DATETIME(6) NOT NULL COMMENT '事件UTC时间',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `occurred_at`),
  CONSTRAINT `ck_core_security_event_1` CHECK (outcome IN ('DENIED','FAILED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_write_fence` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `fence_key` VARCHAR(100) NOT NULL COMMENT '例如policy:tenant或cap:evaluation',
  `epoch` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '递增代次',
  `enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '开关',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'CAS修订号',
  PRIMARY KEY (`tenant_id`, `fence_key`),
  CONSTRAINT `ck_core_write_fence_1` CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_core_execution_grant` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `scope_key` VARCHAR(100) NOT NULL COMMENT '可信策略作用域：tenant或school:<内部学校ID>；不能从客户端声明授权',
  `capability_key` VARCHAR(100) NOT NULL COMMENT '稳定能力',
  `manifest_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际执行发布',
  `epoch` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '资格代次',
  `enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前资格',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'CAS修订号',
  PRIMARY KEY (`tenant_id`, `scope_key`, `capability_key`, `manifest_digest`),
  CONSTRAINT `ck_core_execution_grant_1` CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_release_current` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `deployment_key` VARCHAR(100) NOT NULL COMMENT '部署标识',
  `scope_key` VARCHAR(100) NOT NULL COMMENT '产品/能力作用域',
  `stable_release_id` BIGINT UNSIGNED NOT NULL COMMENT '稳定发布',
  `desired_generation` BIGINT UNSIGNED NOT NULL COMMENT '控制面期望代次',
  `observed_generation` BIGINT UNSIGNED NOT NULL COMMENT '数据面已确认代次',
  `state` VARCHAR(16) NOT NULL COMMENT 'RECONCILING/READY/PAUSED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'CAS修订号',
  PRIMARY KEY (`tenant_id`, `deployment_key`, `scope_key`),
  KEY `ix_1` (`tenant_id`, `deployment_key`, `stable_release_id`),
  CONSTRAINT `ck_ops_release_current_1` CHECK (state IN ('RECONCILING','READY','PAUSED')),
  CONSTRAINT `ck_ops_release_current_2` CHECK (observed_generation <= desired_generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_rollout` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '统一ID；API字符串',
  `deployment_key` VARCHAR(100) NOT NULL COMMENT '部署',
  `scope_key` VARCHAR(100) NOT NULL COMMENT '能力范围',
  `stable_release_id` BIGINT UNSIGNED NOT NULL COMMENT '基线',
  `candidate_release_id` BIGINT UNSIGNED NOT NULL COMMENT '候选',
  `cohort_policy_json` JSON NOT NULL COMMENT '分组单位、稳定种子、允许学校与有效期',
  `analysis_policy_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '评估策略摘要',
  `status` VARCHAR(20) NOT NULL COMMENT 'DRAFT/PILOT/OBSERVING/PAUSED/COMPLETED/ABORTED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'CAS修订号',
  PRIMARY KEY (`tenant_id`, `id`),
  UNIQUE KEY `uq_1` (`tenant_id`, `deployment_key`, `scope_key`, `id`),
  KEY `ix_1` (`tenant_id`, `deployment_key`, `stable_release_id`),
  KEY `ix_2` (`tenant_id`, `deployment_key`, `candidate_release_id`),
  CONSTRAINT `ck_ops_rollout_1` CHECK (status IN ('DRAFT','PILOT','OBSERVING','PAUSED','COMPLETED','ABORTED')),
  CONSTRAINT `ck_ops_rollout_2` CHECK (stable_release_id <> candidate_release_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_rollout_binding` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `deployment_key` VARCHAR(100) NOT NULL COMMENT '部署',
  `scope_key` VARCHAR(100) NOT NULL COMMENT '能力范围',
  `rollout_id` BIGINT UNSIGNED NULL COMMENT '当前计划；NULL表示没有灰度',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'CAS修订号',
  PRIMARY KEY (`tenant_id`, `deployment_key`, `scope_key`),
  KEY `ix_1` (`tenant_id`, `deployment_key`, `scope_key`, `rollout_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_ops_rollout_decision` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `id` BIGINT UNSIGNED NOT NULL COMMENT '统一ID；API字符串',
  `rollout_id` BIGINT UNSIGNED NOT NULL COMMENT '计划',
  `input_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '输入摘要',
  `policy_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '策略摘要',
  `window_start` DATETIME(6) NOT NULL COMMENT 'UTC窗口开始',
  `window_end` DATETIME(6) NOT NULL COMMENT 'UTC窗口结束',
  `outcome` VARCHAR(16) NOT NULL COMMENT 'CONTINUE/PAUSE/ABORT',
  `reason_codes_json` JSON NOT NULL COMMENT '稳定原因码',
  `evidence_ref` VARCHAR(190) NOT NULL COMMENT '受控证据对象引用',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC入库；连接初始化强制',
  PRIMARY KEY (`tenant_id`, `id`),
  KEY `ix_1` (`tenant_id`, `rollout_id`),
  CONSTRAINT `ck_ops_rollout_decision_1` CHECK (outcome IN ('CONTINUE','PAUSE','ABORT')),
  CONSTRAINT `ck_ops_rollout_decision_2` CHECK (window_end > window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `ed_identity_account_current` (
  `tenant_id` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可信租户，不裁剪不规范化',
  `host_user_key` VARCHAR(100) NOT NULL COMMENT '可信宿主账号键',
  `account_link_id` BIGINT UNSIGNED NULL COMMENT '唯一当前账号关联；空表示当前未绑定，不能回退到任意历史关系',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC建行时间；连接初始化强制',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前指针CAS版本',
  PRIMARY KEY (`tenant_id`, `host_user_key`),
  KEY `ix_1` (`tenant_id`, `host_user_key`, `account_link_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE `ed_identity_account_link` ADD CONSTRAINT `fk_identity_account_link_1` FOREIGN KEY (`tenant_id`, `person_id`) REFERENCES `ed_identity_person` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_identity_external_map` ADD CONSTRAINT `fk_identity_external_map_1` FOREIGN KEY (`tenant_id`, `person_id`) REFERENCES `ed_identity_person` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_identity_relation` ADD CONSTRAINT `fk_identity_relation_1` FOREIGN KEY (`tenant_id`, `subject_person_id`) REFERENCES `ed_identity_person` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_identity_relation` ADD CONSTRAINT `fk_identity_relation_2` FOREIGN KEY (`tenant_id`, `related_person_id`) REFERENCES `ed_identity_person` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_org_unit` ADD CONSTRAINT `fk_education_org_unit_1` FOREIGN KEY (`tenant_id`, `parent_id`) REFERENCES `ed_education_org_unit` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_term` ADD CONSTRAINT `fk_education_term_1` FOREIGN KEY (`tenant_id`, `school_id`) REFERENCES `ed_education_org_unit` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_class` ADD CONSTRAINT `fk_education_class_1` FOREIGN KEY (`tenant_id`, `school_id`) REFERENCES `ed_education_org_unit` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_class` ADD CONSTRAINT `fk_education_class_2` FOREIGN KEY (`tenant_id`, `school_id`, `term_id`) REFERENCES `ed_education_term` (`tenant_id`, `school_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_enrollment` ADD CONSTRAINT `fk_education_enrollment_1` FOREIGN KEY (`tenant_id`, `school_id`) REFERENCES `ed_education_org_unit` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_membership` ADD CONSTRAINT `fk_education_membership_1` FOREIGN KEY (`tenant_id`, `school_id`, `enrollment_id`) REFERENCES `ed_education_enrollment` (`tenant_id`, `school_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_membership` ADD CONSTRAINT `fk_education_membership_2` FOREIGN KEY (`tenant_id`, `school_id`, `class_id`) REFERENCES `ed_education_class` (`tenant_id`, `school_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_membership` ADD CONSTRAINT `fk_education_membership_3` FOREIGN KEY (`tenant_id`, `school_id`) REFERENCES `ed_education_org_unit` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_education_assignment` ADD CONSTRAINT `fk_education_assignment_1` FOREIGN KEY (`tenant_id`, `class_id`) REFERENCES `ed_education_class` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_evidence_binding` ADD CONSTRAINT `fk_evidence_binding_1` FOREIGN KEY (`tenant_id`, `evidence_id`) REFERENCES `ed_evidence_object` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_config_binding` ADD CONSTRAINT `fk_config_binding_1` FOREIGN KEY (`tenant_id`, `release_id`) REFERENCES `ed_config_release` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_indicator` ADD CONSTRAINT `fk_eval_indicator_1` FOREIGN KEY (`tenant_id`, `rubric_id`) REFERENCES `ed_eval_rubric` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement` ADD CONSTRAINT `fk_eval_judgement_1` FOREIGN KEY (`tenant_id`, `observation_id`) REFERENCES `ed_eval_observation` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement` ADD CONSTRAINT `fk_eval_judgement_2` FOREIGN KEY (`tenant_id`, `indicator_id`) REFERENCES `ed_eval_indicator` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement` ADD CONSTRAINT `fk_eval_judgement_3` FOREIGN KEY (`tenant_id`, `series_id`, `supersedes_id`) REFERENCES `ed_eval_judgement` (`tenant_id`, `series_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement` ADD CONSTRAINT `fk_eval_judgement_4` FOREIGN KEY (`tenant_id`, `series_id`, `observation_id`, `indicator_id`) REFERENCES `ed_eval_judgement_series` (`tenant_id`, `id`, `observation_id`, `indicator_id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_duty_issue` ADD CONSTRAINT `fk_duty_issue_1` FOREIGN KEY (`tenant_id`, `duplicate_of`) REFERENCES `ed_duty_issue` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_duty_action` ADD CONSTRAINT `fk_duty_action_1` FOREIGN KEY (`tenant_id`, `issue_id`) REFERENCES `ed_duty_issue` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_workflow_case` ADD CONSTRAINT `fk_workflow_case_1` FOREIGN KEY (`tenant_id`, `definition_id`) REFERENCES `ed_workflow_definition` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_workflow_task` ADD CONSTRAINT `fk_workflow_task_1` FOREIGN KEY (`tenant_id`, `case_id`) REFERENCES `ed_workflow_case` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_workflow_action` ADD CONSTRAINT `fk_workflow_action_1` FOREIGN KEY (`tenant_id`, `case_id`) REFERENCES `ed_workflow_case` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_workflow_action` ADD CONSTRAINT `fk_workflow_action_2` FOREIGN KEY (`tenant_id`, `case_id`, `task_id`) REFERENCES `ed_workflow_task` (`tenant_id`, `case_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_integration_import_batch` ADD CONSTRAINT `fk_integration_import_batch_1` FOREIGN KEY (`tenant_id`, `source_id`) REFERENCES `ed_integration_source` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_integration_import_error` ADD CONSTRAINT `fk_integration_import_error_1` FOREIGN KEY (`tenant_id`, `batch_id`) REFERENCES `ed_integration_import_batch` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_growth_report_item` ADD CONSTRAINT `fk_growth_report_item_1` FOREIGN KEY (`tenant_id`, `report_id`) REFERENCES `ed_growth_report` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_reward_entry` ADD CONSTRAINT `fk_reward_entry_1` FOREIGN KEY (`tenant_id`, `account_id`) REFERENCES `ed_reward_account` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_reward_entry` ADD CONSTRAINT `fk_reward_entry_2` FOREIGN KEY (`tenant_id`, `account_id`, `reverses_entry_id`) REFERENCES `ed_reward_entry` (`tenant_id`, `account_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement_series` ADD CONSTRAINT `fk_eval_judgement_series_1` FOREIGN KEY (`tenant_id`, `observation_id`) REFERENCES `ed_eval_observation` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement_series` ADD CONSTRAINT `fk_eval_judgement_series_2` FOREIGN KEY (`tenant_id`, `indicator_id`) REFERENCES `ed_eval_indicator` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_eval_judgement_series` ADD CONSTRAINT `fk_eval_judgement_series_3` FOREIGN KEY (`tenant_id`, `id`, `current_judgement_id`) REFERENCES `ed_eval_judgement` (`tenant_id`, `series_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_ops_release_current` ADD CONSTRAINT `fk_ops_release_current_1` FOREIGN KEY (`tenant_id`, `deployment_key`, `stable_release_id`) REFERENCES `ed_ops_release_binding` (`tenant_id`, `deployment_key`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_ops_rollout` ADD CONSTRAINT `fk_ops_rollout_1` FOREIGN KEY (`tenant_id`, `deployment_key`, `stable_release_id`) REFERENCES `ed_ops_release_binding` (`tenant_id`, `deployment_key`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_ops_rollout` ADD CONSTRAINT `fk_ops_rollout_2` FOREIGN KEY (`tenant_id`, `deployment_key`, `candidate_release_id`) REFERENCES `ed_ops_release_binding` (`tenant_id`, `deployment_key`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_ops_rollout_binding` ADD CONSTRAINT `fk_ops_rollout_binding_1` FOREIGN KEY (`tenant_id`, `deployment_key`, `scope_key`, `rollout_id`) REFERENCES `ed_ops_rollout` (`tenant_id`, `deployment_key`, `scope_key`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_ops_rollout_decision` ADD CONSTRAINT `fk_ops_rollout_decision_1` FOREIGN KEY (`tenant_id`, `rollout_id`) REFERENCES `ed_ops_rollout` (`tenant_id`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE `ed_identity_account_current` ADD CONSTRAINT `fk_identity_account_current_1` FOREIGN KEY (`tenant_id`, `host_user_key`, `account_link_id`) REFERENCES `ed_identity_account_link` (`tenant_id`, `host_user_key`, `id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
