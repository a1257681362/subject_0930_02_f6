package com.evops.aqua.med;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.disease.entity.DiseaseObservation;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.mapper.PondMapper;
import com.evops.aqua.mapper.SeedlingBatchMapper;
import com.evops.aqua.med.entity.DrugBatch;
import com.evops.aqua.med.entity.DrugBatchLock;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.entity.Requisition;
import com.evops.aqua.med.entity.RequisitionItem;
import com.evops.aqua.med.entity.WithdrawalLock;
import com.evops.aqua.med.mapper.DrugBatchLockMapper;
import com.evops.aqua.med.mapper.DrugBatchMapper;
import com.evops.aqua.med.mapper.MedChangeEvidenceMapper;
import com.evops.aqua.med.mapper.RequisitionItemMapper;
import com.evops.aqua.med.mapper.RequisitionMapper;
import com.evops.aqua.med.mapper.WithdrawalLockMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 养殖用药管理验收（aqua-6）。
 * 主线：药品目录(剂量+休药期)与批号三量账 → 领用单关联池塘/病情/药品 → 按剂量×水体估算用量
 * → 提交即 FEFO 预占批号、计算休药期并锁定预计出池日(多药叠加取最晚)，半成品停留待处理不扣库存
 * → 过期/库存不足拦截不留半占用 → 驳回释放、撤回仅限未审核单 → 审核通过才扣库存并确认锁定
 * → 休药期未满禁止出池 → 兽医看处方、仓管只看库存 → 库存与领用单全程留证可追溯。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MedicationRequisitionAcceptanceTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PondMapper pondMapper;
    @Autowired
    private SeedlingBatchMapper seedlingBatchMapper;
    @Autowired
    private DrugBatchMapper drugBatchMapper;
    @Autowired
    private DrugBatchLockMapper drugBatchLockMapper;
    @Autowired
    private RequisitionMapper requisitionMapper;
    @Autowired
    private RequisitionItemMapper requisitionItemMapper;
    @Autowired
    private WithdrawalLockMapper withdrawalLockMapper;
    @Autowired
    private MedChangeEvidenceMapper evidenceMapper;

    private final List<String> report = new ArrayList<>();
    private String sessionCookie;

    private Long pondId;
    private Long seedlingBatchId;
    private Long observationId;
    private Long drugAId;   // 碘制剂 0.5mg/L 休药10天
    private Long drugBId;   // 中草药 0.2mg/L 休药20天
    private Long drugCId;   // 仅有过期批号
    private Long drugDId;   // 批号库存不足
    private Long batchA1;   // A 未过期批号 1000g
    private Long batchA0;   // A 已过期批号
    private Long batchB1;   // B 批号 1000g
    private Long batchC0;   // C 过期批号
    private Long batchD1;   // D 少量批号 50g
    private Long r1;        // 主领用单 10-05 A+B
    private Long r2;        // 10-06 A，驳回
    private Long r3;        // 10-08 A，提交后撤回
    private Long r4;        // 草稿撤回

    @Test
    void acceptanceMedicationRequisition() {
        checkpoint("CP0", "登录认证", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("username", "bootstrap");
            body.put("password", "bootstrap");
            ResponseEntity<String> resp = call(HttpMethod.POST, "/api/auth/login", body);
            assertSuccess(json(resp), "登录");
            List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
            check(cookies != null && !cookies.isEmpty(), "登录成功但未下发会话Cookie");
            sessionCookie = cookies.get(0).split(";")[0];
        });

        checkpoint("CP1", "建池并投苗一批（投苗2026-10-01，计划出池2026-12-01）", () -> {
            Map<String, Object> pond = new HashMap<>();
            pond.put("pondCode", "MED-P01");
            pond.put("pondName", "用药1号池");
            pond.put("seaArea", "东山湾海区");
            pond.put("waterDepthM", "2.00");
            pond.put("salinityPpt", "28.5");
            JsonNode pr = json(call(HttpMethod.POST, "/api/aqua/ponds", pond));
            assertSuccess(pr, "建池");
            pondId = pr.path("data").path("id").asLong();
            check(pondMapper.selectById(pondId) != null, "养殖池应落库");

            Map<String, Object> batch = new HashMap<>();
            batch.put("batchNo", "MED-FISH-01");
            batch.put("pondId", pondId);
            batch.put("species", "LARGE_YELLOW_CROAKER");
            batch.put("seedSource", "大黄鱼苗");
            batch.put("quantity", 50000);
            batch.put("stockDate", "2026-10-01");
            batch.put("plannedHarvestDate", "2026-12-01");
            JsonNode br = json(call(HttpMethod.POST, "/api/aqua/batches", batch));
            assertSuccess(br, "投苗");
            seedlingBatchId = br.path("data").path("id").asLong();
        });

        checkpoint("CP2", "登记病情观察单（同池同批，高风险），作为领用单关联病情", () -> {
            Map<String, Object> obs = new HashMap<>();
            obs.put("pondId", pondId);
            obs.put("batchId", seedlingBatchId);
            obs.put("observeDate", "2026-10-04");
            obs.put("symptoms", "体表溃疡、摄食下降");
            obs.put("riskLevel", "HIGH");
            obs.put("observer", "兽医李兰");
            obs.put("requestId", "med-obs-1");
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/disease/observations", obs));
            assertSuccess(resp, "登记病情观察单");
            observationId = resp.path("data").path("id").asLong();
            check(DiseaseObservation.RISK_HIGH.equals(
                    resp.path("data").path("riskLevel").asText()), "病情风险等级应为HIGH");
        });

        checkpoint("CP3", "登记药品目录：A碘制剂0.5mg/L休药10天、B中草药0.2mg/L休药20天（另备C/D）", () -> {
            drugAId = registerDrug("DRUG-A", "聚维酮碘", "0.5", 10);
            drugBId = registerDrug("DRUG-B", "大黄末", "0.2", 20);
            drugCId = registerDrug("DRUG-C", "过期药", "1.0", 5);
            drugDId = registerDrug("DRUG-D", "紧缺药", "1.0", 5);
            check(drugAId != null && drugBId != null, "药品目录应落库");
        });

        checkpoint("CP4", "药品批号入库三量账：A有未过期1000g+已过期批号；B1000g；C仅过期；D仅50g", () -> {
            batchA0 = stockBatch("MB-A-EXPIRED", drugAId, "500", "2026-10-04");
            batchA1 = stockBatch("MB-A-FRESH", drugAId, "1000", "2026-11-30");
            batchB1 = stockBatch("MB-B-1", drugBId, "1000", "2026-11-30");
            batchC0 = stockBatch("MB-C-EXPIRED", drugCId, "500", "2026-10-01");
            batchD1 = stockBatch("MB-D-SMALL", drugDId, "50", "2026-11-30");
            DrugBatch a1 = drugBatchMapper.selectById(batchA1);
            check(a1.getQuantityLocked().signum() == 0 && a1.getQuantityConsumed().signum() == 0,
                    "新批号锁定量/消耗量初始应为0");
        });

        checkpoint("CP5", "建领用单(草稿)关联池塘/病情/A+B两药，水体100m³：估算A=50g、B=20g，叠加最晚休药结束10-25", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                    requisitionBody("2026-10-05", "100", item(drugAId), item(drugBId)),
                    "med-r1-create"));
            assertSuccess(resp, "建领用单R1");
            r1 = resp.path("data").path("id").asLong();
            check(Requisition.STATUS_DRAFT.equals(resp.path("data").path("status").asText()),
                    "新建领用单应为草稿DRAFT");
            check(new BigDecimal("70.00").compareTo(
                    resp.path("data").path("estimatedQuantityG").decimalValue()) == 0,
                    "合计估算应为0.5*100+0.2*100=70g，实际="
                            + resp.path("data").path("estimatedQuantityG").asText());
            check("2026-10-25".equals(resp.path("data").path("withdrawalEndDate").asText()),
                    "多药叠加应取最晚休药结束日(B药用20天=10-25)，实际="
                            + resp.path("data").path("withdrawalEndDate").asText());
            check(resp.path("data").path("observationId").asLong() == observationId,
                    "领用单应关联病情观察单");
            List<RequisitionItem> items = itemsOf(r1);
            check(items.size() == 2, "应有2条处方明细");
            RequisitionItem a = items.get(0);
            check(new BigDecimal("50.00").compareTo(a.getEstimatedQuantityG()) == 0
                            && a.getWithdrawalDays() == 10
                            && "2026-10-15".equals(a.getWithdrawalEndDate().toString()),
                    "A药估算50g、休药结束10-15");
            check(new BigDecimal("0.5").compareTo(a.getDosageMgPerL()) == 0, "应冻结A药剂量快照0.5mg/L");
            // 草稿阶段不得预占库存、不得写休药期锁定
            check(drugBatchMapper.selectById(batchA1).getQuantityLocked().signum() == 0,
                    "草稿态不得预占批号库存");
            check(withdrawalLockMapper.selectCount(new QueryWrapper<WithdrawalLock>()
                    .eq("requisition_id", r1)) == 0L, "草稿态不得写休药期锁定");
            // requestId 幂等：重复建单返回同一单
            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                    requisitionBody("2026-10-05", "100", item(drugAId), item(drugBId)),
                    "med-r1-create"));
            check(again.path("data").path("id").asLong() == r1, "重复建单应幂等返回首次领用单");
        });

        checkpoint("CP6", "提交：按FEFO预占未过期批号(A1/B1)，库存只锁不扣；提交即写休药期锁定并锁定出池日", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r1 + "/submit",
                    action("med-r1-submit", "兽医李兰")));
            assertSuccess(resp, "提交R1");
            check(Requisition.STATUS_SUBMITTED.equals(resp.path("data").path("status").asText()),
                    "提交后应为待审核SUBMITTED");
            DrugBatch a1 = drugBatchMapper.selectById(batchA1);
            DrugBatch b1 = drugBatchMapper.selectById(batchB1);
            check(new BigDecimal("50").compareTo(a1.getQuantityLocked()) == 0
                            && a1.getQuantityConsumed().signum() == 0,
                    "A1应预占50g且尚未消耗，实际锁定=" + a1.getQuantityLocked());
            check(new BigDecimal("20").compareTo(b1.getQuantityLocked()) == 0
                            && b1.getQuantityConsumed().signum() == 0,
                    "B1应预占20g且尚未消耗");
            check(drugBatchMapper.selectById(batchA0).getQuantityLocked().signum() == 0,
                    "FEFO不得占用已过期批号A0");
            // 提交即写休药期锁定（最晚结束10-25）
            WithdrawalLock lock = withdrawalLockMapper.selectOne(new QueryWrapper<WithdrawalLock>()
                    .eq("requisition_id", r1));
            check(lock != null && WithdrawalLock.STATUS_ACTIVE.equals(lock.getStatus())
                            && "2026-10-25".equals(lock.getWithdrawalEndDate().toString()),
                    "提交即应写有效休药期锁定，最晚结束10-25");
            // 批号占用明细指向未过期批号
            List<DrugBatchLock> aLocks = locksOfItem(itemsOf(r1).get(0).getId());
            check(aLocks.size() == 1 && aLocks.get(0).getDrugBatchId().equals(batchA1),
                    "A药预占明细必须指向未过期批号A1");
        });

        checkpoint("CP7", "审批只完成一半（仍待处理）：休药期已锁，10-20出池被拦截；尝试独立事务留证", () -> {
            check(Requisition.STATUS_SUBMITTED.equals(requisitionMapper.selectById(r1).getStatus()),
                    "未审核单应停留SUBMITTED待处理");
            check(drugBatchMapper.selectById(batchA1).getQuantityConsumed().signum() == 0,
                    "待处理期间不得扣减库存");
            JsonNode h = json(call(HttpMethod.POST, "/api/aqua/batches/" + seedlingBatchId + "/harvest",
                    harvestBody("2026-10-20")));
            check(!h.path("success").asBoolean(true), "休药期未满(10-20<10-25)出池应被拦截");
            check(h.path("message").asText().contains("2026-10-25")
                            && h.path("message").asText().contains("休药"),
                    "拦截提示应含最晚休药结束日：" + h.path("message").asText());
            check(SeedlingBatch.STATUS_STOCKED.equals(
                    seedlingBatchMapper.selectById(seedlingBatchId).getStatus()), "被拦后批次应仍在养");
            Long blocked = evidenceMapper.selectCount(new QueryWrapper<MedChangeEvidence>()
                    .eq("action", MedChangeEvidence.ACTION_HARVEST_BLOCKED));
            check(blocked != null && blocked >= 1, "被拦截的出池尝试应独立事务留证HARVEST_BLOCKED");
        });

        checkpoint("CP8", "过期批号与库存不足拦截：C仅过期批号、D仅50g而需100g，提交失败且不留半占用", () -> {
            Long rc = createDraft("2026-10-05", "100", item(drugCId), "med-rc-create");
            JsonNode cResp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + rc + "/submit",
                    action("med-rc-submit", "兽医李兰")));
            check(!cResp.path("success").asBoolean(true), "仅过期批号时提交应被拦截");
            check(cResp.path("message").asText().contains("未过期"),
                    "应提示无未过期批号：" + cResp.path("message").asText());
            check(Requisition.STATUS_DRAFT.equals(requisitionMapper.selectById(rc).getStatus()),
                    "拦截后领用单应仍是草稿");
            check(drugBatchMapper.selectById(batchC0).getQuantityLocked().signum() == 0,
                    "过期批号不得产生占用");

            Long rd = createDraft("2026-10-05", "100", item(drugDId), "med-rd-create");
            JsonNode dResp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + rd + "/submit",
                    action("med-rd-submit", "兽医李兰")));
            check(!dResp.path("success").asBoolean(true), "可用库存不足时提交应被拦截");
            check(dResp.path("message").asText().contains("库存不足"),
                    "应提示库存不足：" + dResp.path("message").asText());
            check(drugBatchMapper.selectById(batchD1).getQuantityLocked().signum() == 0,
                    "库存不足不得产生部分占用（事务回滚）");
            check(drugBatchLockMapper.selectCount(new QueryWrapper<DrugBatchLock>()
                    .eq("status", DrugBatchLock.STATUS_LOCKED)) == 2L,
                    "失败提交不得残留占用行，全库预占行应仍只有R1的2条(A/B)");
        });

        checkpoint("CP9", "审核驳回：缺意见被拒；合法驳回释放全部预占并停用休药期锁定", () -> {
            r2 = createDraft("2026-10-06", "100", item(drugAId), "med-r2-create");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r2 + "/submit",
                    action("med-r2-submit", "兽医李兰"))), "提交R2");
            check(new BigDecimal("100").compareTo(drugBatchMapper.selectById(batchA1).getQuantityLocked()) == 0,
                    "R2提交后A1累计预占应为R1(50)+R2(50)=100");
            WithdrawalLock l2Before = withdrawalLockMapper.selectOne(new QueryWrapper<WithdrawalLock>()
                    .eq("requisition_id", r2));
            check(l2Before != null && WithdrawalLock.STATUS_ACTIVE.equals(l2Before.getStatus())
                            && "2026-10-16".equals(l2Before.getWithdrawalEndDate().toString()),
                    "R2提交后应有有效休药期锁定至10-16");

            JsonNode noRemark = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r2 + "/review",
                    review(false, "仓管赵强", null, "med-r2-reject-denied")));
            check(!noRemark.path("success").asBoolean(true), "驳回缺审核意见应被拒绝");

            JsonNode rejected = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r2 + "/review",
                    review(false, "主管周正", "病情复查无需用药，驳回", "med-r2-reject")));
            assertSuccess(rejected, "合法驳回R2");
            check(Requisition.STATUS_REJECTED.equals(rejected.path("data").path("status").asText()),
                    "驳回后状态应为REJECTED");
            check(new BigDecimal("50").compareTo(drugBatchMapper.selectById(batchA1).getQuantityLocked()) == 0,
                    "驳回应释放R2的50g预占，A1恢复为R1的50");
            check(WithdrawalLock.STATUS_REVOKED.equals(withdrawalLockMapper.selectOne(
                    new QueryWrapper<WithdrawalLock>().eq("requisition_id", r2)).getStatus()),
                    "驳回应停用R2休药期锁定（只置状态不删行）");
        });

        checkpoint("CP10", "撤回只能撤销未审核单：草稿可撤回、已提交撤回落锁释放预占；已审核不可撤回", () -> {
            // 草稿态可直接撤回
            r4 = createDraft("2026-10-09", "100", item(drugAId), "med-r4-create");
            JsonNode revokeDraft = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r4 + "/revoke",
                    revoke("兽医李兰", "med-r4-revoke")));
            assertSuccess(revokeDraft, "草稿态撤回");
            check(Requisition.STATUS_REVOKED.equals(revokeDraft.path("data").path("status").asText()),
                    "草稿撤回后应为REVOKED");

            // 已提交待审核单可撤回：释放预占、停用休药期锁定
            r3 = createDraft("2026-10-08", "100", item(drugAId), "med-r3-create");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r3 + "/submit",
                    action("med-r3-submit", "兽医李兰"))), "提交R3");
            check(new BigDecimal("100").compareTo(drugBatchMapper.selectById(batchA1).getQuantityLocked()) == 0,
                    "R3提交后A1累计预占应为50(R1)+50(R3)=100");
            JsonNode revokeR3 = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r3 + "/revoke",
                    revoke("兽医李兰", "med-r3-revoke")));
            assertSuccess(revokeR3, "撤回待审核R3");
            check(Requisition.STATUS_REVOKED.equals(revokeR3.path("data").path("status").asText()),
                    "R3撤回后应为REVOKED");
            check(new BigDecimal("50").compareTo(drugBatchMapper.selectById(batchA1).getQuantityLocked()) == 0,
                    "撤回应释放R3预占，A1仅剩R1的50");
            check(WithdrawalLock.STATUS_REVOKED.equals(withdrawalLockMapper.selectOne(
                    new QueryWrapper<WithdrawalLock>().eq("requisition_id", r3)).getStatus()),
                    "撤回应停用R3休药期锁定");
            // 重复撤回幂等不多做
            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r3 + "/revoke",
                    revoke("兽医李兰", "med-r3-revoke")));
            check(Requisition.STATUS_REVOKED.equals(again.path("data").path("status").asText()),
                    "重复撤回应幂等返回REVOKED");
        });

        checkpoint("CP11", "审核通过才扣库存：R1预占50/20转消耗，休药期锁定确认；重复审核幂等不重复扣", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r1 + "/review",
                    review(true, "主管周正", "同意用药", "med-r1-approve")));
            assertSuccess(resp, "审核通过R1");
            check(Requisition.STATUS_APPROVED.equals(resp.path("data").path("status").asText()),
                    "审核通过后应为APPROVED");
            DrugBatch a1 = drugBatchMapper.selectById(batchA1);
            DrugBatch b1 = drugBatchMapper.selectById(batchB1);
            check(new BigDecimal("50").compareTo(a1.getQuantityConsumed()) == 0
                            && a1.getQuantityLocked().signum() == 0,
                    "A1应实耗50g、预占清零，实际消耗=" + a1.getQuantityConsumed());
            check(new BigDecimal("20").compareTo(b1.getQuantityConsumed()) == 0
                            && b1.getQuantityLocked().signum() == 0,
                    "B1应实耗20g、预占清零");
            check(WithdrawalLock.STATUS_ACTIVE.equals(withdrawalLockMapper.selectOne(
                    new QueryWrapper<WithdrawalLock>().eq("requisition_id", r1)).getStatus()),
                    "审核通过后R1休药期锁定应保持有效");

            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r1 + "/review",
                    review(true, "主管周正", "重复审核", "med-r1-approve")));
            assertSuccess(again, "重复审核请求");
            check(new BigDecimal("50").compareTo(drugBatchMapper.selectById(batchA1).getQuantityConsumed()) == 0
                            && new BigDecimal("20").compareTo(drugBatchMapper.selectById(batchB1).getQuantityConsumed()) == 0,
                    "重复审核不得重复扣减库存");
        });

        checkpoint("CP12", "已审核单不可撤回；被驳回/撤回单与已审核单的出池约束只取有效锁定最晚日", () -> {
            JsonNode revokeApproved = json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + r1 + "/revoke",
                    revoke("兽医李兰", "med-r1-revoke-denied")));
            check(!revokeApproved.path("success").asBoolean(true), "已审核通过单撤回应被拒绝");
            check(revokeApproved.path("message").asText().contains("已审核"),
                    "应提示已审核不能撤回：" + revokeApproved.path("message").asText());
            check(Requisition.STATUS_APPROVED.equals(requisitionMapper.selectById(r1).getStatus()),
                    "拒绝撤回后R1应仍为APPROVED");
            // R2(驳回,结束10-16)、R3(撤回,结束10-18)锁定均已停用，有效锁定只剩R1的10-25
            JsonNode early = json(call(HttpMethod.POST, "/api/aqua/batches/" + seedlingBatchId + "/harvest",
                    harvestBody("2026-10-22")));
            check(!early.path("success").asBoolean(true)
                            && early.path("message").asText().contains("2026-10-25"),
                    "已停用锁定不得放行，10-22仍应被R1的10-25拦截");
        });

        checkpoint("CP13", "休药期满允许出池（10-25即最晚结束日当天可出），批次封存", () -> {
            JsonNode ok = json(call(HttpMethod.POST, "/api/aqua/batches/" + seedlingBatchId + "/harvest",
                    harvestBody("2026-10-25")));
            assertSuccess(ok, "休药期满(10-25)出池");
            check(SeedlingBatch.STATUS_HARVESTED.equals(
                    seedlingBatchMapper.selectById(seedlingBatchId).getStatus()), "出池后批次应封存HARVESTED");
        });

        checkpoint("CP14", "角色视图：兽医看处方明细（剂量/休药期/病情），仓管只看库存字段", () -> {
            JsonNode vet = json(call(HttpMethod.GET,
                    "/api/aqua/med/requisitions/" + r1 + "?role=VET", null));
            assertSuccess(vet, "兽医视图");
            check(vet.path("data").path("requisition").path("status").asText().equals("APPROVED"),
                    "兽医视图应含完整领用单");
            check(vet.path("data").path("observation").path("symptoms").asText().contains("溃疡"),
                    "兽医视图应含病情症状");
            JsonNode vetItem = vet.path("data").path("items").get(0).path("item");
            check(vetItem.path("dosageMgPerL").decimalValue().compareTo(new BigDecimal("0.5")) == 0
                            && vetItem.path("withdrawalDays").asInt() == 10,
                    "兽医视图应含剂量与休药期处方明细");
            check(vet.path("data").path("items").get(0).path("locks").get(0)
                    .path("batchStock").path("availableQuantityG").decimalValue().compareTo(new BigDecimal("950")) == 0,
                    "兽医也能看到批号实耗后可用量=1000-50=950g");

            JsonNode wh = json(call(HttpMethod.GET,
                    "/api/aqua/med/requisitions/" + r1 + "?role=WAREHOUSE", null));
            assertSuccess(wh, "仓管视图");
            check(wh.path("data").path("requisitionNo").asText().startsWith("MED-"),
                    "仓管视图应含单号等库存视角抬头");
            check(wh.path("data").path("requisition").isMissingNode()
                            && wh.path("data").path("observation").isMissingNode()
                            && wh.path("data").path("withdrawalEndDate").isMissingNode(),
                    "仓管视图不得含处方抬头（完整单/病情/休药期）");
            JsonNode whItem = wh.path("data").path("items").get(0);
            check(whItem.path("drugName").asText().length() > 0
                            && whItem.path("estimatedQuantityG").decimalValue().compareTo(new BigDecimal("50")) == 0
                            && whItem.path("allocations").get(0).path("batchNo").asText().length() > 0,
                    "仓管视图应含药名/应发量/批号分配");
            check(whItem.path("dosageMgPerL").isMissingNode()
                            && whItem.path("withdrawalDays").isMissingNode()
                            && whItem.path("observationId").isMissingNode(),
                    "仓管视图不得暴露剂量/休药期/病情等处方字段");
            check(whItem.path("allocations").get(0).path("batchStock").path("quantityConsumed")
                    .decimalValue().compareTo(new BigDecimal("50")) == 0,
                    "仓管视图应含批号三量账（实耗50g）");

            JsonNode whList = json(call(HttpMethod.GET,
                    "/api/aqua/med/requisitions?role=WAREHOUSE&pondId=" + pondId, null));
            check(whList.path("data").isArray() && whList.path("data").size() >= 1
                            && whList.path("data").get(0).path("requisitionNo").asText().length() > 0
                            && whList.path("data").get(0).path("requisition").isMissingNode(),
                    "仓管列表视图也只给库存字段");
        });

        checkpoint("CP15", "全程留证可追溯：库存与领用单动作齐备，可按requestId追到审核扣减链", () -> {
            check(countEvidence(MedChangeEvidence.ACTION_SUBMIT, null) >= 3, "应保留提交证据(R1/R2/R3)");
            check(countEvidence(MedChangeEvidence.ACTION_APPROVE, r1) == 1, "R1审核通过证据应为1条");
            check(countEvidence(MedChangeEvidence.ACTION_REJECT, r2) == 1, "R2驳回证据应为1条");
            check(countEvidence(MedChangeEvidence.ACTION_REVOKE, null) >= 2, "应保留撤回证据(R3/R4)");
            check(countEvidence(MedChangeEvidence.ACTION_CONSUME, null) == 2,
                    "审核扣库存证据应为A/B各1条共2条");
            check(evidenceMapper.selectCount(new QueryWrapper<MedChangeEvidence>()
                    .eq("entity_type", MedChangeEvidence.ENTITY_DRUG_BATCH)
                    .eq("action", MedChangeEvidence.ACTION_LOCK)) == 4L,
                    "批号预占证据应为R1两条+R2+R3共4条");
            check(evidenceMapper.selectCount(new QueryWrapper<MedChangeEvidence>()
                    .eq("entity_type", MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK)
                    .eq("action", MedChangeEvidence.ACTION_LOCK)) == 3L,
                    "提交即锁出池日证据应为R1/R2/R3共3条");

            JsonNode chain = json(call(HttpMethod.GET,
                    "/api/aqua/med/evidence?requestId=med-r1-approve", null));
            assertSuccess(chain, "按requestId追溯R1审核链");
            boolean hasConsume = false;
            boolean hasOperator = false;
            for (JsonNode n : chain.path("data")) {
                if (MedChangeEvidence.ACTION_CONSUME.equals(n.path("action").asText())) {
                    hasConsume = true;
                }
                if ("主管周正".equals(n.path("operator").asText())) {
                    hasOperator = true;
                }
            }
            check(hasConsume, "R1审核链应能追到批号实耗(CONSUME)证据");
            check(hasOperator, "证据必须带操作者(审核人主管周正)，不丢操作者");

            // 批号三量账可追溯：A1 入库1000、实耗50、可用950
            DrugBatch a1 = drugBatchMapper.selectById(batchA1);
            check(new BigDecimal("1000").compareTo(a1.getQuantityIn()) == 0
                            && new BigDecimal("50").compareTo(a1.getQuantityConsumed()) == 0
                            && new BigDecimal("950").compareTo(a1.availableQuantity()) == 0,
                    "A1批号库存应可追溯：入库1000/实耗50/可用950");
        });

        report.add("验收结论：全部检查点通过——领用单关联池塘病情药品批号、按剂量×水体估算用量、提交即FEFO预占并计算休药期锁定出池日(多药叠加取最晚)、半成品停留待处理不扣库存、过期/库存不足拦截不留半占用、撤回仅限未审核单、审核通过才扣库存、休药期未满禁止出池、兽医看处方仓管看库存、库存与领用单全程留证可追溯");
        printReport();
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private Long registerDrug(String code, String name, String dosage, int withdrawalDays) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("drugCode", code);
        m.put("drugName", name);
        m.put("dosageMgPerL", dosage);
        m.put("withdrawalDays", withdrawalDays);
        m.put("manufacturer", "海西兽药厂");
        m.put("operator", "仓管赵强");
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/drugs", m));
        assertSuccess(resp, "登记药品" + code);
        return resp.path("data").path("id").asLong();
    }

    private Long stockBatch(String no, Long drugId, String qty, String expiry) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("batchNo", no);
        m.put("drugId", drugId);
        m.put("supplier", "海西兽药厂");
        m.put("quantityIn", qty);
        m.put("productionDate", "2026-08-01");
        m.put("expiryDate", expiry);
        m.put("operator", "仓管赵强");
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/drug-batches", m));
        assertSuccess(resp, "药品批号入库" + no);
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> item(Long drugId) {
        Map<String, Object> m = new HashMap<>();
        m.put("drugId", drugId);
        return m;
    }

    private Map<String, Object> requisitionBody(String date, String volume, Map<String, Object>... items) {
        Map<String, Object> m = new HashMap<>();
        m.put("pondId", pondId);
        m.put("batchId", seedlingBatchId);
        m.put("observationId", observationId);
        m.put("useDate", date);
        m.put("waterVolumeM3", volume);
        m.put("applicant", "兽医李兰");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> it : items) {
            list.add(it);
        }
        m.put("items", list);
        return m;
    }

    private Long createDraft(String date, String volume, Map<String, Object> item, String requestId)
            throws Exception {
        Map<String, Object> body = requisitionBody(date, volume, item);
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions", body, requestId));
        assertSuccess(resp, "建草稿" + requestId);
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> action(String requestId, String operator) {
        Map<String, Object> m = new HashMap<>();
        m.put("requestId", requestId);
        m.put("operator", operator);
        return m;
    }

    private Map<String, Object> review(boolean approved, String reviewer, String remark, String requestId) {
        Map<String, Object> m = new HashMap<>();
        m.put("approved", approved);
        m.put("reviewer", reviewer);
        m.put("remark", remark);
        m.put("requestId", requestId);
        return m;
    }

    private Map<String, Object> revoke(String operator, String requestId) {
        Map<String, Object> m = new HashMap<>();
        m.put("operator", operator);
        m.put("requestId", requestId);
        return m;
    }

    private Map<String, Object> harvestBody(String date) {
        Map<String, Object> m = new HashMap<>();
        m.put("harvestDate", date);
        m.put("quantity", 50000);
        return m;
    }

    private List<RequisitionItem> itemsOf(Long requisitionId) {
        return requisitionItemMapper.selectList(new QueryWrapper<RequisitionItem>()
                .eq("requisition_id", requisitionId).orderByAsc("id"));
    }

    private List<DrugBatchLock> locksOfItem(Long itemId) {
        return drugBatchLockMapper.selectList(new QueryWrapper<DrugBatchLock>()
                .eq("requisition_item_id", itemId).orderByAsc("id"));
    }

    private long countEvidence(String action, Long entityId) {
        QueryWrapper<MedChangeEvidence> wrapper = new QueryWrapper<MedChangeEvidence>()
                .eq("action", action);
        if (entityId != null) {
            wrapper.eq("entity_id", entityId);
        }
        Long c = evidenceMapper.selectCount(wrapper);
        return c == null ? 0L : c;
    }

    private void checkpoint(String id, String name, CheckpointBody body) {
        try {
            body.run();
            String line = "通过 " + id + " " + name;
            report.add(line);
            System.out.println("[验收] " + line);
        } catch (Throwable t) {
            String stop = "停点 " + id + "「" + name + "」失败，原因：" + t.getMessage();
            report.add(stop);
            report.add("验收结论：未通过，后续检查点未执行");
            printReport();
            fail("验收" + stop);
        }
    }

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 养殖用药管理验收报告 ==========\n");
        for (String line : report) {
            sb.append(line).append('\n');
        }
        sb.append("===========================================\n");
        System.out.println(sb);
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body) {
        return call(method, url, body, null);
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        if (body instanceof Map && requestId != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapped = (Map<String, Object>) body;
            mapped.put("requestId", requestId);
        }
        return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccess(JsonNode node, String what) {
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(what + "接口返回失败：" + node.path("message").asText());
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private interface CheckpointBody {
        void run() throws Exception;
    }
}
