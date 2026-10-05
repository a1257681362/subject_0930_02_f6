# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 海水养殖台账（aqua-1）

模块包路径 `com.evops.aqua`，已打通：建池 → 投苗 → 日常记录 → 饲料/用药 → 抽检 → 换池 → 出池。成本核算留待后续版本。

- 养殖池档案：水深、盐度、所属海区（`POST /api/aqua/ponds`）
- 投苗批次：苗种来源、入池数量、计划出池日；批次编号全局唯一（`POST /api/aqua/batches`）
- 饲料、用药、日常、抽检：按池按批留痕（`/api/aqua/records/**`），批次不在该池或已出池会被拒绝
- 换池：`POST /api/aqua/batches/{id}/transfer` 自动生成转移记录并更新批次所在池
- 出池：`POST /api/aqua/batches/{id}/harvest` 生成出池记录，批次封存
- 原始投苗单不可删除：无 DELETE 接口，表上无删除标记
- 批次全链路追溯：`GET /api/aqua/batches/{id}/trace`

接口需先登录：`POST /api/auth/login`（bootstrap / bootstrap）。

验收：`mvn test -Dtest=AquaLedgerAcceptanceTest`，从养殖池档案入口追到投苗批次落库，逐检查点输出报告，失败即停并给出停点。

## 水质采样计划与缺测补录（aqua-2）

模块包路径 `com.evops.aqua.wq`，在台账之上打通：分阶段规则版本 → 采样计划 → 按池生成任务 → 草稿/提交 → 缺测补录 → 主管审核 → 池塘健康曲线，全程留证。

- 指标规则版本化（`POST /api/aqua/wq/rule-versions`）：不同养殖阶段配置采样频率（间隔天数）与指标正常范围；新版本发布后旧版本自动停用，**新版本只作用于未开始任务**——在执行计划的版本指针切换，从未录入过的草稿任务按新版本重建指标快照；已暂存/已提交/已审核任务冻结旧版本不变。
- 采样计划（`/api/aqua/wq/plans`）：绑定规则版本与养殖阶段、覆盖日期区间与池塘；`POST /{id}/tasks/generate` **按池按频率生成任务**，同计划同池同日幂等，重复请求不产生第二条结果。
- 采样执行（`/api/aqua/wq/tasks/{id}`）：`PUT /draft` 可只录部分指标暂存草稿；`POST /submit` 提交时每个指标必须有值，**缺测项必须说明原因**，否则拒绝；重复提交返回同一任务。
- 缺测/历史补录（`POST /api/aqua/wq/backfills`，requestId 强制幂等）：**只能追加一行，绝不覆盖原采样值**；重复补录请求返回首次记录。
- 主管审核（`POST /api/aqua/wq/tasks/{id}/review`）：驳回必须填意见且退回草稿；**审核通过后已测值才进入池塘健康曲线**（`GET /api/aqua/wq/ponds/{pondId}/health-curve`，缺测项不入曲线，取原值不取补录值）；重复审核不重复写曲线。
- 所有修改保留证据：生成/暂存/提交/补录/驳回/通过/版本切换均写 `t_wq_change_evidence`（含操作前后快照与幂等请求号），可经 `GET /api/aqua/wq/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=WaterQualitySamplingAcceptanceTest`，14 个检查点逐段输出报告，失败即停并给出停点。

## 饲料批号与配方投喂（aqua-3）

模块包路径 `com.evops.aqua.feed`，在台账之上打通：饲料批号三量账 → 配方按苗种/日龄/水温版本化 → 投喂单准备即冻结配方快照 → 锁定占用批号 → 临时减量审批 → 执行转消耗，日汇总试喂与正式分列，全程留证。

- 饲料批号（`POST /api/aqua/feed/batches`）：一批一行三量账（入库 / 锁定占用 / 已消耗），可用量=入库−锁定−已消耗；批号不可删除。锁定时按 **FEFO 先到期先用** 从未过期批号占用，可跨批号拼配；**批号已过期或可用库存不足一律拦截，且不残留半占用**（同事务回滚）。
- 投喂配方版本（`POST /api/aqua/feed/formulas`）：按苗种发布，版本下以「日龄闭区间 + 水温闭区间」配置饲料与计划投喂量，命中多行取区间最窄（最具体）者。**配方调整发布新版本，同苗种旧版本自动停用**；投喂单建立即冻结当时版本指针与计划量快照，**历史投喂永远引用旧版本快照，不受新版本影响**。
- 投喂单（`/api/aqua/feed/orders`）：`POST /prepare` 按池按批按日，日龄由投苗日与投喂日推算（不由前端填写），区分 `FORMAL` 正式投喂 / `TRIAL` 试喂，同池同批同日同类型幂等；`POST /{id}/lock` 按 FEFO 占用批号；**`POST /{id}/execute` 只有锁定后才允许执行**（草稿态直接执行拒绝），执行把锁定占用转为批号实际消耗，重复执行幂等；`POST /{id}/unlock` 释放占用退回草稿。
- 临时减量（`POST /api/aqua/feed/orders/{id}/items/{itemId}/reduction`）：仅锁定态可登记，实际量必须小于锁定量，**必须登记减量原因与审批人**，登记后释放差额占用，执行按减量后实际量消耗。
- 日汇总（`GET /api/aqua/feed/orders/daily-summary`）：按池按日只统计已执行投喂，**试喂与正式投喂严格分列、绝不混算**。
- 海水养殖管理权限（`/api/aqua/feed/access`）：`POST /tighten` 收紧、`POST /relax` 放开（均须登记原因）。**收紧后饲料批号停止推进**——新批号入库、锁定占用、执行消耗一律拦截；**已确认的配方版本与历史投喂事实保留不变**。
- 全程留证：入库/锁定/解锁/减量/执行/版本停用/权限收紧放开均写 `t_feed_change_evidence`（含操作前后 JSON 快照与幂等请求号），可经 `GET /api/aqua/feed/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=FeedFormulaFeedingAcceptanceTest`，17 个检查点逐段输出报告，失败即停并给出停点。

## 疫病观察与隔离池调度（aqua-4）

模块包路径 `com.evops.aqua.disease`，在台账之上打通：登记隔离池档案 → 发现异常症状生成观察单 → 按风险等级推荐隔离池 → 调度前校验 → 调入隔离池 → 兽医复核解除回原池，数量逐笔记录，定时复查任务幂等，全程留证且不丢操作者。

- 池塘隔离调度档案（`/api/aqua/disease/pond-guards`）：按池登记隔离池角色、容量、消毒有效期、调入权限，操作者必填，变更留证。
- 疫病观察单（`POST /api/aqua/disease/observations`，requestId 强制幂等）：发现异常症状即生成，记录症状、风险等级（LOW/MEDIUM/HIGH）与观察人；重复提交返回同一单。
- 按风险等级推荐隔离池（`GET /api/aqua/disease/observations/{id}/recommend-pools`）：低风险建议原地观察；中风险仅推荐同海区隔离池；高风险推荐全部可用隔离池（同海区优先）；被排除候选逐条说明原因。
- 隔离调度（`POST /api/aqua/disease/observations/{id}/dispatch`，requestId 幂等）：**转移前校验池容量、消毒状态、目标池调入权限与混养占用，失败时一次性说明全部冲突原因**并留失败证据（独立事务，不随业务回滚）；成功生成隔离单并同步台账转移记录。
- 隔离中管控：批次**禁止换池（混养）与出池**（台账接口直接拦截）；隔离池在隔离期间**禁止投苗混养**，他批不得再调入同一隔离池。
- 解除隔离（`POST /api/aqua/disease/isolations/{id}/release`）：**必须由兽医复核**——复核兽医与复核意见缺一不可；解除后批次回原池，重复解除幂等不多写。
- 数量逐笔账（`GET /api/aqua/disease/isolations/movements`）：原池与隔离池之间调入、回池各记一笔，数量与操作者随行，只追加。
- 定时复查任务（Quartz，cron 由 `aqua.disease.followup-scan-cron` 配置，默认每日 02:10）：按日扫描未结观察单生成复查任务，**task_no 按观察单+日期唯一——应用重启、任务重跑都不多写记录**；记录操作者固定为调度系统标识并保留原观察人，**不丢操作者**。`POST /api/aqua/disease/followup-tasks/scan` 可人工触发同一逻辑核查。
- 全程留证：档案变更/观察单/调度/调度失败/解除/复查任务均写 `t_disease_change_evidence`（含操作前后快照、幂等请求号与操作者），可经 `GET /api/aqua/disease/evidence` 追溯。

验收：`mvn test -Dtest=DiseaseIsolationAcceptanceTest`，15 个检查点逐段输出报告，失败即停并给出停点。

## 潮汐窗口排班（aqua-5）

模块包路径 `com.evops.aqua.tide`，在台账之上打通：潮汐表/禁作天气/人员班次/设备登记 → 按海区潮汐生成换水候选 → 叠加天气与班次形成可执行窗口 → 排班绑窗口池塘设备 → 发布后审批改期 → 自动顺延 → 临时关闭通知班组留回执，全程留证。

- 潮汐表（`POST /api/aqua/tide/events`）：按海区逐条登记高潮/低潮事件；requestId 幂等，同海区同时刻同潮型自然键幂等，重复登记返回首次记录。
- 禁作天气（`/api/aqua/tide/weather-bans`）与人员班次（`/api/aqua/tide/shifts`）：按海区登记禁作时段、按班组登记值勤时段，均 requestId 幂等。
- 窗口生成（`POST /api/aqua/tide/windows/generate`）：取海区当日高潮事件向前后各推 margin 分钟得候选时段，**剔除与禁作天气重叠的候选、要求班组班次全覆盖**，被排除候选逐条说明原因；同一潮汐事件只生成一个窗口，重复生成不多写。
- **统一口径（临界时间归属）**：所有时段一律按半开区间 `[start, end)` 判定重叠——端点相接的天气/排班不算冲突；班次覆盖按闭区间——端点相等算覆盖；**窗口归属日 = 窗口开始时间所在自然日**（跨午夜窗口归开始日），窗口查询、排班、通知全部按此口径归属。
- 排班（`/api/aqua/tide/schedules`）：窗口+池塘+设备，校验窗口开放、池塘海区一致、设备可用；**同一设备同一时间只能服务一口池**（半开区间判定，端点相接允许），同一池塘同一窗口只排一班。
- 发布与改期：`POST /{id}/publish` 发布；`POST /{id}/reschedule` 改期——**已发布排班必须登记调度审批人与审批意见**，否则拒绝；待发布排班直接改。
- 自动顺延（`POST /{id}/postpone`）：系统自动挑选同海区下一个设备空闲的开放窗口生成新排班（origin 链接、继承发布态）；**原计划行的时间/池塘/设备一律不变**，仅状态置 POSTPONED；requestId 幂等。
- 临时关闭（`POST /api/aqua/tide/windows/{id}/close`）：登记关闭人与原因，向窗口内受影响班组逐条发通知；班组签收（`POST /api/aqua/tide/notifications/{id}/ack`）**留回执**（签收人/时间/回执备注）；重复关闭不多发、重复签收不覆盖首次回执。
- 全程留证：潮汐/天气/班次/设备登记、成窗、排班创建/发布/改期/顺延、关窗、通知、签收均写 `t_tide_change_evidence`（含操作前后快照与幂等请求号），可经 `GET /api/aqua/tide/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=TideWindowScheduleAcceptanceTest`，11 个检查点逐段输出报告，失败即停并给出停点。

## 养殖用药管理（aqua-6）

模块包路径 `com.evops.aqua.med`，在台账之上打通：药品目录（剂量+休药期）→ 药品批号三量账 → 领用单关联池塘/病情/药品批号，按剂量×水体估算用量 → 提交即 FEFO 预占批号并计算休药期、锁定预计出池日 → 审核通过才扣库存，全程留证、按角色分视图。

- 药品目录（`POST /api/aqua/med/drugs`）：登记泼洒剂量（mg/L）与休药期天数；药品批号（`POST /api/aqua/med/drug-batches`）一批一行三量账（入库/锁定占用/已消耗，单位 g），可用量=入库−锁定−已消耗；**按 FEFO 先到期先用占用未过期批号，批号过期或可用库存不足一律拦截且不残留半占用**（同事务回滚）。
- 用药领用单（`/api/aqua/med/requisitions`）：关联池塘、投苗批次与病情（疫病观察单，须同池同批）；建单为草稿，**按 剂量(mg/L)×水体(m³) 估算用量(g)** 并冻结剂量/休药期快照，同池同批同日幂等（requestId）。
- 提交与休药期：`POST /{id}/submit` 即按 FEFO 预占批号、**计算休药期并锁定预计出池日**——休药结束日=用药日+休药天数，**不同药品叠加取最晚结束日**；提交即写批次休药期锁定（`t_med_withdrawal_lock`）。**库存此时只锁不扣**。
- 审批只完成一半停留待处理：提交后单据保持 `SUBMITTED`，不扣库存；台账出池（`POST /api/aqua/batches/{id}/harvest`）校验休药期，**预计/实际出池日早于最晚休药结束日一律拦截**（被拦尝试独立事务留证），休药期满当天方可出池。
- 审核（`POST /{id}/review`）：**只有审核通过才把批号预占转为实际消耗（库存扣减与领用审核保持一致）**并确认休药期锁定；驳回必须填意见、释放全部预占并停用休药期锁定。重复审核幂等不重复扣。
- 撤回（`POST /{id}/revoke`）：**只能撤销未审核单**（草稿/已提交待审核）；已提交单撤回释放全部预占、停用休药期锁定；已审核（通过/驳回）单不可撤回。
- 角色视图：`GET /api/aqua/med/requisitions/{id}?role=VET` 兽医查看处方明细（药品/剂量/休药期/病情/批号）；`?role=WAREHOUSE` 仓管只看库存字段（药名/应发实发数量/批号三量账），不暴露剂量、休药期、病情。
- 全程留证：药品登记/批号入库/预占/释放/消耗、领用单建单/提交/通过/驳回/撤回、休药期锁定/解锁、出池被拦均写 `t_med_change_evidence`（含操作前后快照、幂等请求号与操作者），可经 `GET /api/aqua/med/evidence` 按对象或 requestId 追溯，药品库存与领用单全链路可查。

验收：`mvn test -Dtest=MedicationRequisitionAcceptanceTest`，16 个检查点逐段输出报告，失败即停并给出停点。



