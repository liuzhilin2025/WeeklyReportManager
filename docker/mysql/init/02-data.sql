
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `t_department`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_department` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门ID',
  `name` varchar(50) NOT NULL COMMENT '部门名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `t_department` WRITE;
/*!40000 ALTER TABLE `t_department` DISABLE KEYS */;
INSERT INTO `t_department` VALUES (1,'技术部'),(2,'产品部'),(3,'市场部');
/*!40000 ALTER TABLE `t_department` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `t_device`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_device` (
  `id` varchar(10) NOT NULL COMMENT '设备编号，如 D001',
  `name` varchar(50) NOT NULL COMMENT '设备名称',
  `power` double NOT NULL COMMENT '功率（瓦特）',
  `type` varchar(20) NOT NULL COMMENT '设备类型：Light/AC/TV',
  `extra` varchar(100) DEFAULT NULL COMMENT '额外属性（亮度/温度/尺寸）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `t_device` WRITE;
/*!40000 ALTER TABLE `t_device` DISABLE KEYS */;
/*!40000 ALTER TABLE `t_device` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `t_employee`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_employee` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `name` varchar(50) NOT NULL COMMENT '姓名',
  `department` varchar(50) NOT NULL COMMENT '部门',
  `salary` double NOT NULL COMMENT '薪资',
  `hire_date` date NOT NULL COMMENT '入职日期',
  `dept_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `t_employee` WRITE;
/*!40000 ALTER TABLE `t_employee` DISABLE KEYS */;
INSERT INTO `t_employee` VALUES (2,'王五','技术部',8000,'2024-01-01',1),(3,'张三','技术部',1000,'2022-07-21',1),(4,'李四','产品部',9000,'2023-06-01',2);
/*!40000 ALTER TABLE `t_employee` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `t_records`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_records` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `request_id` varchar(32) NOT NULL COMMENT '请求ID（业务唯一标识）',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `device_sn` varchar(50) NOT NULL COMMENT '设备编号',
  `question_text` text NOT NULL COMMENT '用户问题文本',
  `answer_text` text COMMENT 'AI回答文本',
  `asr_duration_ms` int DEFAULT '0' COMMENT '语音识别耗时',
  `llm_duration_ms` int DEFAULT '0' COMMENT '大模型回答耗时',
  `tts_duration_ms` int DEFAULT '0' COMMENT '语音合成耗时',
  `total_duration_ms` int DEFAULT '0' COMMENT '对话总耗时',
  `success` tinyint(1) DEFAULT '1' COMMENT '是否成功',
  `error_code` varchar(20) DEFAULT NULL COMMENT '错误码',
  `error_message` varchar(255) DEFAULT NULL COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '对话发生时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_request_id` (`request_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_device_sn` (`device_sn`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `t_records` WRITE;
/*!40000 ALTER TABLE `t_records` DISABLE KEYS */;
INSERT INTO `t_records` VALUES (1,'REQ-20260805-0001',1001,'AI-TOY-001','今天天气怎么样？','今天深圳天气晴朗，适合出门。',320,1200,800,2320,1,NULL,NULL,'2026-08-05 10:30:00'),(2,'REQ-20260808-0001',1001,'AI-TOY-001','中国的首都是哪座城市？','中国的首都是北京。',268,750,500,1518,1,NULL,NULL,'2026-08-08 11:21:42'),(3,'REQ-20260808-0002',1002,'AI-TOY-002','中国的母亲河是哪条河？','中国的母亲河是黄河，孕育了华夏文明。',430,1000,609,2039,1,NULL,NULL,'2026-08-08 11:25:15'),(4,'REQ-20260810-0001',1002,'AI-TOY-002','你是谁？','我是小爱，你的桌面宠物。',200,400,350,950,1,NULL,NULL,'2026-08-10 09:38:15'),(5,'REQ-20260810-0002',1001,'AI-TOY-002','评价一下文化大革命中的四人帮。','',600,1800,0,2400,0,'404','此问题涉及到政治问题，过于敏感。','2026-08-10 09:45:58'),(6,'REQ-20260812-0001',1002,'AI-TOY-002','当年政府里的人是怎么蒙蔽毛泽东的？','',1200,1800,0,3000,0,'404','此问题涉及到政治问题，过于敏感。','2026-08-12 13:28:54'),(7,'REQ-20260807-0001',1001,'AI-TOY-001','中国历史上第一位皇帝是谁？','秦始皇嬴政是中国历史上第一位皇帝。',500,1000,300,1800,1,NULL,NULL,'2026-08-07 14:48:07'),(8,'REQ-20260807-0002',1001,'AI-TOY-001','世界上面积最大的三个国家是哪三个？','俄罗斯、加拿大以及中国是世界上面积最大的三个国家。',299,1000,330,1629,1,NULL,NULL,'2026-08-07 15:24:47'),(9,'REQ-20260814-0001',1001,'AI-TOY-001','世界上最大的沙漠是哪个？','北非的撒哈拉沙漠是世界上最大的沙漠。',450,677,345,1472,1,NULL,NULL,'2026-08-14 11:24:37'),(10,'REQ-20260814-0002',1002,'AI-TOY-002','请你分别说出英国、法国、德国的首都。','英国的首都是伦敦，法国的首都是巴黎，德国的首都是柏林。',500,985,400,1885,1,NULL,NULL,'2026-08-14 11:27:07');
/*!40000 ALTER TABLE `t_records` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `t_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `age` int DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `t_user` WRITE;
/*!40000 ALTER TABLE `t_user` DISABLE KEYS */;
INSERT INTO `t_user` VALUES (1,'王五',30,'wangwu@example.com'),(2,'李四',19,'lisi980511@example.com'),(3,'张三',35,'1375382387@qq.com'),(4,'赵六',24,'zhaoliu@163.com');
/*!40000 ALTER TABLE `t_user` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `t_weeklyreport`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_weeklyreport` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(100) NOT NULL,
  `week_start_date` date NOT NULL,
  `overall_progress` text NOT NULL,
  `weekly_work_report` text NOT NULL,
  `next_week_plan` text NOT NULL,
  `other` text,
  `status` varchar(20) DEFAULT 'Editing',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `submitted_at` datetime DEFAULT NULL,
  `user_id` bigint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_week` (`user_id`,`week_start_date`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `t_weeklyreport` WRITE;
/*!40000 ALTER TABLE `t_weeklyreport` DISABLE KEYS */;
INSERT INTO `t_weeklyreport` VALUES (1,'08.17~08.21','2026-08-17','深圳','上海','稻花香里说丰年','牧童遥指杏花村','SUBMITTED','2026-08-19 09:05:11','2026-08-21 18:30:58','2026-08-21 18:30:58',1),(2,'08.17~08.21','2026-08-17','git','1001','1','11444','SUBMITTED','2026-08-19 10:05:48','2026-08-21 16:25:49','2026-08-21 16:25:49',2),(3,'08.17~08.21','2026-08-17','纤云弄巧，飞星传恨，云汉迢迢暗度','金风玉露一相逢，便胜却人间无数','无','','SUBMITTED','2026-08-19 10:51:10','2026-08-19 10:51:10','2026-08-28 17:21:32',3),(4,'08.17~08.21','2026-08-17','娥儿雪柳黄金缕','宝马雕车香满路','众里寻他千百度','那人却在，灯火阑珊处','SUBMITTED','2026-08-19 14:26:36','2026-08-21 10:31:33','2026-08-21 10:31:33',4),(5,'08.03~08.07','2026-08-03','完成了需求文档编写','完成了数据库设计','开始开发后端接口','无','SUBMITTED','2026-08-07 10:57:30','2026-08-07 11:00:25','2026-08-07 17:00:00',1),(6,'08.03~08.07','2026-08-03','今天是周一','一硫二硝三木炭','暂时没想好',NULL,'SUBMITTED','2026-08-07 16:30:40','2026-08-07 16:52:39','2026-08-07 17:00:00',2),(7,'08.03~08.07','2026-08-03','回首','轰轰烈烈','的人生',NULL,'SUBMITTED','2026-08-07 16:48:40','2026-08-07 17:05:34','2026-08-07 17:10:09',3),(8,'08.03~08.07','2026-08-03','地中海气候','result','1','斯柯达','EDITING','2026-08-07 10:05:13','2026-08-07 10:30:25','2026-08-07 17:00:00',4),(9,'08.10~08.14','2026-08-10','人','民','万','岁','SUBMITTED','2026-08-11 10:05:13','2026-08-12 10:30:25','2026-08-12 15:00:00',1),(10,'08.17~08.21','2026-08-17','红豆生南国','春来发几枝','愿君多采撷','此物最相思','SUBMITTED','2026-08-21 11:02:14','2026-08-21 11:02:14','2026-08-21 18:03:39',5),(11,'08.24~08.28','2026-08-24','1','122','1','','SUBMITTED','2026-08-25 17:29:53','2026-08-25 17:29:53','2026-08-28 17:21:52',1),(12,'08.24~08.28','2026-08-24','2','211','2','','EDITING','2026-08-25 17:29:58','2026-08-25 17:29:58',NULL,2),(13,'08.24~08.28','2026-08-24','2','2','2','','SUBMITTED','2026-08-28 17:41:56','2026-08-28 17:41:58','2026-08-28 17:41:59',3),(14,'08.31~09.04','2026-08-31','飞到底','果','天','是','EDITING','2026-08-31 13:52:33','2026-09-01 13:38:53',NULL,1),(15,'08.31~09.04','2026-08-31','床前明月光','疑似地上霜','举头望明月','低头思故乡','EDITING','2026-09-01 13:48:29','2026-09-01 13:48:29',NULL,2),(19,'09.07~09.11','2026-09-07','完成 AI 功能集成与 Redis 缓存优化，整体进度 80%。','1. 接入 DeepSeek 大模型，完成 AI 辅助撰写、润色、完整性检查三个接口。\n2. 实现历史周报摘要与团队周报摘要，输出结构化 JSON。\n3. 优化 Redis 缓存策略，接口平均响应从 200ms 降至 5ms。\n4. 补充全局异常处理与参数校验，覆盖 9 类异常场景。','1. 完善 AI 结果缓存。\n2. 增加异步生成与重试机制。','需要前端配合联调 AI 接口，测试同学帮忙验证边界场景。','SUBMITTED','2026-09-07 17:49:51','2026-09-11 17:12:21','2026-09-11 17:12:22',1),(20,'09.07~09.11','2026-09-07','完成周报页面重构与 AI 按钮接入，进度 70%。','1. 重构“填写周报”页面，增加 AI 辅助撰写、润色、完整性检查按钮。\n2. 实现“团队情况”页面按周查看与 AI 汇总入口。\n3. 优化表单字数统计与校验提示，提升交互体验。\n4. 与后端联调部分接口，AI 接口尚未完全对接。','完成 AI 接口联调，增加加载状态与错误提示。','需要后端提供 AI 接口的详细文档和错误码，方便处理异常情况。','SUBMITTED','2026-09-07 17:52:54','2026-09-11 17:12:24','2026-09-11 17:12:25',2),(21,'09.07~09.11','2026-09-07','完成核心功能回归测试与 AI 功能初步验证，进度 60%。','1. 对周报增删改查、状态流转、分页查询进行回归测试。\n2. 验证 Redis 缓存命中率与数据一致性。\n3. 初步测试 AI 辅助撰写接口，发现超长输入时返回格式不稳定。\n4. 整理测试用例，覆盖 9 类异常场景。','补充 AI 功能的边界测试，包括空输入、超长文本、模型超时等。','需要后端确认 AI 接口的输入长度限制与重试策略。','SUBMITTED','2026-09-11 16:39:28','2026-09-11 17:12:30','2026-09-11 17:12:30',3),(22,'09.07~09.11','2026-09-07','完成 Docker 容器化部署与监控配置，进度 90%。','1. 编写 Dockerfile 与 docker-compose.yml，实现 MySQL、Redis 及应用一键启动。\n2. 配置日志采集与基础监控，覆盖接口调用与异常记录。\n3. 推送镜像至阿里云容器镜像仓库，完成部署文档初稿。\n4. 协助后端排查缓存连接异常问题。','1. 完善部署文档。\n2. 增加自动化部署脚本与回滚方案。','需要后端提供 AI 接口的资源消耗（Token 用量）预估，便于容量规划。','SUBMITTED','2026-09-11 17:09:52','2026-09-11 17:12:33','2026-09-11 17:12:33',4),(23,'09.07~09.11','2026-09-07','已完成需求梳理与进度跟踪，整体项目进度为 75%。','1. 组织需求评审会，明确 AI 功能优先级与验收标准。\n2. 跟踪各模块进度，协调前后端联调时间。\n3. 整理用户反馈，识别周报填写效率为主要痛点。\n4. 输出项目周报模板与 AI 功能使用说明初稿。','1. 推动 AI 功能上线试用。\n2. 收集用户反馈并迭代。','需要前后端尽快完成联调，测试同步介入，确保按时交付。','SUBMITTED','2026-09-11 17:12:02','2026-09-11 17:12:17','2026-09-11 17:12:17',5),(24,'09.14~09.18','2026-09-14','本周继续推进 AI 相关功能开发，完成缓存、异步生成、重试机制、接口联调及边界场景修复等工作，AI 功能基本收尾，整体进度约 95%。','1. 实现 AI 摘要结果缓存：以 userId 加周次拼接 key 存入 Redis，过期时间设为一天，二次请求无需重复调用大模型，响应速度明显提升；修复摘要内容过长导致的序列化报错，调整 Jackson 配置并加入默认类型信息。\n2. 实现异步生成：为解决团队周报生成耗时数秒的问题，尝试 @Async 注解加线程池方案，因事务不生效改为 CompletableFuture 手写异步；补充异步任务异常捕获与日志，避免接口返回 500。\n3. 增加重试机制：使用 Spring Retry 配置最大 3 次重试、间隔 1 秒；测试中发现重试范围覆盖了数据库查询等前置操作，已改为仅对 AI 调用段重试。\n4. 完成前后端联调：针对前端传入问题文本为空导致后端报错的问题，增加空值校验并返回友好错误提示；调整返回结构中若干字段为数组，便于前端展示。\n5. 修复边界问题：处理超长文本（约五千字）输入截断不完善导致 AI 返回乱码的问题，调整 limit 方法对输入做截断并补充清晰错误提示；修复保存草稿时四个字段全空报错的问题，改为提示“请至少填写一项”。','1. 为 AI 接口增加监控，统计调用次数与 Token 消耗情况。\n2. 补充 AI 功能相关文档。\n3. 关注老板提到的新功能需求，待明确后评估并安排开发。','暂无阻塞项。新功能需求尚未明确，需等待进一步信息后再评估排期。','SUBMITTED','2026-09-14 11:38:51','2026-09-15 13:46:21','2026-09-18 17:34:30',1),(25,'09.14~09.18','2026-09-14','本周完成3个AI接口（润色、完整性检查、摘要）的前端联调，已全部调通并进入稳定阶段；完成8个AI按钮的加载与错误交互优化、12个错误码的中文映射，修复5个响应式布局问题，配合测试覆盖9类异常场景，前端提示覆盖率达100%。整体AI功能前端开发与联调基本完成，具备提测条件。','1. 与后端对接，获取润色、完整性检查、摘要3个AI接口的文档及错误码，启动联调。\n2. 完成跨域与代理配置，3个接口全部调通，记录平均响应时间：润色约2.5秒、完整性检查约3.2秒、摘要约4.8秒。\n3. 完成加载状态与错误提示：为8个AI相关按钮添加转圈动画和禁用效果，防止重复点击；将后端12个错误码统一映射为中文文案（如“AI服务繁忙，请稍后重试”“输入内容过长，请精简后重试”等）。\n4. 联调历史摘要与团队摘要，发现后端字段名中英文不一致导致前端解析失败率约30%，与后端统一字段名后失败率降至0。\n5. 修复5个响应式布局问题（如表格在手机上溢出），配合测试完成9类异常场景验证（空输入、超长文本、模型超时等），前端提示覆盖率达100%。','1. 补充AI功能使用说明文档，预计2页左右，覆盖功能入口、操作流程与常见提示说明。\n2. 评估并优化摘要接口响应时间，目标从4.8秒优化至3秒以内，必要时与后端协同排查耗时点。','摘要接口当前平均响应时间约4.8秒，明显高于其他接口，可能影响用户体验，需在下周重点优化；历史摘要与团队摘要的字段名规范已与后端对齐，后续需保持接口字段命名一致性，避免再次出现解析失败。','SUBMITTED','2026-09-15 13:49:25','2026-09-15 13:49:25','2026-09-18 17:34:37',2),(26,'09.14~09.18','2026-09-14','本周已完成AI润色、完整性检查、摘要三个新功能的测试，并推动修复了测试中发现的主要问题。目前功能基本可用，进入问题修复验证与异常场景补充测试阶段。','1. 完成AI润色、完整性检查、摘要三个新功能的测试，覆盖正常流程。\n2. 发现摘要接口响应慢，平均耗时4.8秒，已记录性能问题。\n3. 发现空输入时后端直接报错且前端无提示，反馈给李四后已增加空值校验和错误提示。\n4. 测试超长文本（五千字）时AI返回乱码，张三调整截断逻辑后问题已修复。\n5. 复现保存草稿四个字段全空时的bug，已改为友好提示。','1. 重新执行异常场景测试，重点覆盖模型超时和网络异常。\n2. 补充完善测试用例，确保异常场景覆盖全面。\n3. 配合前端进行回归测试，验证修复效果和整体功能稳定性。','摘要接口响应慢（平均4.8秒）为性能风险，建议后续优化；模型超时和网络异常的测试环境需提前准备。','SUBMITTED','2026-09-18 18:11:28','2026-09-21 10:22:15','2026-09-18 18:11:28',3),(27,'09.14~09.18','2026-09-14','本周持续维护Docker环境及周边组件，完成Redis缓存配置优化与异步生成问题排查确认，整体进展顺利，环境运行稳定。','1. 持续维护Docker环境，保障服务正常运行。\n2. 优化Redis缓存配置，摘要结果缓存以userId加周次作为key，过期时间设为一天，二次请求响应速度明显提升。\n3. 协助张三排查异步生成问题，确认@Async事务不生效后改为CompletableFuture手动异步，并验证线程池配置正常，不会占满Tomcat线程。\n4. 配合测试重试机制，确认Spring Retry对AI调用段重试不影响数据库查询。','1. 为AI接口增加监控，统计调用次数与Token消耗。\n2. 完善部署文档，补充环境变量等配置说明。','','SUBMITTED','2026-09-18 18:11:53','2026-09-21 10:22:15','2026-09-18 18:11:53',4),(28,'09.14~09.18','2026-09-14','本周主要推进AI功能的前后端联调，整体进度约95%，功能基本收尾。','1. 推进AI功能前后端联调，前端已调通三个接口，错误码映射为中文提示，提升用户体验。\n2. 后端完成缓存、异步、重试等机制，AI功能基本收尾。\n3. 解决字段名中英文不一致导致的解析失败问题，统一字段命名。\n4. 发现摘要接口响应时间偏长，计划下周优化至3秒以内。','1. 编写AI功能使用说明文档。\n2. 了解老板提出的新需求，评估并制定排期。\n3. 优化摘要接口响应时间至3秒以内。','目前无阻塞，等待新需求明确。','SUBMITTED','2026-09-18 18:12:16','2026-09-21 10:22:15','2026-09-18 18:12:16',5);
/*!40000 ALTER TABLE `t_weeklyreport` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `user_name` varchar(32) NOT NULL COMMENT '用户姓名',
  `user_status` varchar(10) NOT NULL COMMENT '用户状态',
  `email` varchar(100) NOT NULL,
  `birth_date` date DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_email` (`email`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `user` WRITE;
/*!40000 ALTER TABLE `user` DISABLE KEYS */;
INSERT INTO `user` VALUES (1,1001,'张三','正常','zhangsan@example.com','2007-07-14'),(2,1002,'李四','正常','lisi@example.com','2004-02-03'),(3,1003,'王五','禁用','wangwu@example.com','2016-03-18'),(4,1004,'赵六','正常','1375382387@qq.com','2014-02-21');
/*!40000 ALTER TABLE `user` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

