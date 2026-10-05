package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.disease.entity.DiseaseObservation;
import com.evops.aqua.disease.service.DiseaseObservationService;
import com.evops.aqua.entity.Pond;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.med.dto.RequisitionCreateRequest;
import com.evops.aqua.med.dto.RequisitionItemInput;
import com.evops.aqua.med.dto.RequisitionReviewRequest;
import com.evops.aqua.med.dto.RequisitionRevokeRequest;
import com.evops.aqua.med.dto.RequisitionSubmitRequest;
import com.evops.aqua.med.entity.Drug;
import com.evops.aqua.med.entity.DrugBatch;
import com.evops.aqua.med.entity.DrugBatchLock;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.entity.Requisition;
import com.evops.aqua.med.entity.RequisitionItem;
import com.evops.aqua.med.entity.WithdrawalLock;
import com.evops.aqua.med.mapper.DrugBatchLockMapper;
import com.evops.aqua.med.mapper.RequisitionItemMapper;
import com.evops.aqua.med.mapper.RequisitionMapper;
import com.evops.aqua.med.mapper.WithdrawalLockMapper;
import com.evops.aqua.service.PondService;
import com.evops.aqua.service.SeedlingBatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用药领用单：关联池塘、病情（疫病观察单）与药品，按剂量×水体估算用量。
 * 草稿 DRAFT（冻结药品/剂量/休药期/用量快照）→ 提交 SUBMITTED（按 FEFO 预占批号、计算休药期）
 * → 审核 APPROVED（预占转实际消耗、写休药期锁定）/ 驳回 REJECTED（释放预占、退回）；
 * 撤回 REVOKE 仅限未审核单。不同药品叠加时休药结束日取最晚者，据此锁定预计出池日。
 * 库存扣减只发生在审核通过；审批只完成一半（仍 SUBMITTED）时系统停留待处理、不扣库存。
 */
@Service
public class RequisitionService {

    /** 角色视图：兽医看处方明细；仓管只看库存字段。 */
    public static final String ROLE_VET = "VET";
    public static final String ROLE_WAREHOUSE = "WAREHOUSE";

    private final RequisitionMapper requisitionMapper;
    private final RequisitionItemMapper itemMapper;
    private final DrugBatchLockMapper lockMapper;
    private final WithdrawalLockMapper withdrawalLockMapper;
    private final DrugService drugService;
    private final DrugBatchService batchService;
    private final MedEvidenceService evidenceService;
    private final SeedlingBatchService seedlingBatchService;
    private final PondService pondService;
    private final DiseaseObservationService observationService;

    public RequisitionService(RequisitionMapper requisitionMapper,
                              RequisitionItemMapper itemMapper,
                              DrugBatchLockMapper lockMapper,
                              WithdrawalLockMapper withdrawalLockMapper,
                              DrugService drugService,
                              DrugBatchService batchService,
                              MedEvidenceService evidenceService,
                              SeedlingBatchService seedlingBatchService,
                              PondService pondService,
                              DiseaseObservationService observationService) {
        this.requisitionMapper = requisitionMapper;
        this.itemMapper = itemMapper;
        this.lockMapper = lockMapper;
        this.withdrawalLockMapper = withdrawalLockMapper;
        this.drugService = drugService;
        this.batchService = batchService;
        this.evidenceService = evidenceService;
        this.seedlingBatchService = seedlingBatchService;
        this.pondService = pondService;
        this.observationService = observationService;
    }

    /**
     * 建单（草稿）：批次须在养且当前在该池；关联病情须同池同批；
     * 按 剂量(mg/L) × 水体(m³) 估算各药用量(g)，并按休药期算出叠加后最晚结束日。
     * requestId 幂等：重复请求返回首次领用单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Requisition create(RequisitionCreateRequest request) {
        if (request.getRequestId() != null && !request.getRequestId().trim().isEmpty()) {
            Requisition same = requisitionMapper.selectOne(new QueryWrapper<Requisition>()
                    .eq("request_id", request.getRequestId()));
            if (same != null) {
                return same;
            }
        }
        SeedlingBatch batch = requireBatchInPond(request.getBatchId(), request.getPondId());
        if (request.getUseDate().isBefore(batch.getStockDate())) {
            throw new IllegalArgumentException("用药日期不能早于投苗日期：" + batch.getStockDate());
        }
        DiseaseObservation observation = null;
        if (request.getObservationId() != null) {
            observation = requireObservation(request.getObservationId(),
                    request.getPondId(), request.getBatchId());
        }
        if (request.getItems().isEmpty()) {
            throw new IllegalArgumentException("领用单至少要有一条处方药品明细");
        }
        Set<Long> drugIds = new HashSet<>();
        for (RequisitionItemInput input : request.getItems()) {
            if (!drugIds.add(input.getDrugId())) {
                throw new IllegalArgumentException("同一领用单中药品不可重复，请合并数量：drugId="
                        + input.getDrugId());
            }
        }

        String requisitionNo = nextRequisitionNo(request.getBatchId(), request.getUseDate());
        LocalDate latestEnd = request.getUseDate();
        BigDecimal totalEstimated = BigDecimal.ZERO;

        // 先按各药品剂量×水体算齐明细用量与叠加最晚休药结束日，再一次性落主单（NOT NULL 列插入即齐备）
        List<RequisitionItem> plannedItems = new ArrayList<>();
        for (RequisitionItemInput input : request.getItems()) {
            Drug drug = drugService.getOrThrow(input.getDrugId());
            Long itemObservationId = input.getObservationId() != null
                    ? input.getObservationId() : request.getObservationId();
            if (itemObservationId != null) {
                requireObservation(itemObservationId, request.getPondId(), request.getBatchId());
            }
            BigDecimal estimated = estimateQuantityGram(drug.getDosageMgPerL(), request.getWaterVolumeM3());
            LocalDate itemEnd = request.getUseDate().plusDays(drug.getWithdrawalDays());
            latestEnd = itemEnd.isAfter(latestEnd) ? itemEnd : latestEnd;
            totalEstimated = totalEstimated.add(estimated);

            RequisitionItem item = new RequisitionItem();
            item.setDrugId(drug.getId());
            item.setDrugName(drug.getDrugName());
            item.setObservationId(itemObservationId);
            item.setDosageMgPerL(drug.getDosageMgPerL());
            item.setWithdrawalDays(drug.getWithdrawalDays());
            item.setEstimatedQuantityG(estimated);
            item.setWithdrawalEndDate(itemEnd);
            plannedItems.add(item);
        }

        Requisition requisition = new Requisition();
        requisition.setRequisitionNo(requisitionNo);
        requisition.setPondId(request.getPondId());
        requisition.setBatchId(request.getBatchId());
        requisition.setObservationId(request.getObservationId());
        requisition.setUseDate(request.getUseDate());
        requisition.setWaterVolumeM3(request.getWaterVolumeM3());
        requisition.setEstimatedQuantityG(totalEstimated);
        requisition.setWithdrawalEndDate(latestEnd);
        requisition.setLockedHarvestDate(latestEnd);
        requisition.setStatus(Requisition.STATUS_DRAFT);
        requisition.setApplicant(request.getApplicant());
        requisition.setRequestId(request.getRequestId());
        requisition.setRemark(request.getRemark());
        requisitionMapper.insert(requisition);

        for (RequisitionItem item : plannedItems) {
            item.setRequisitionId(requisition.getId());
            itemMapper.insert(item);
        }

        evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, requisition.getId(),
                MedChangeEvidence.ACTION_CREATE, request.getRequestId(), request.getApplicant(),
                null, requisition,
                "建用药领用单(草稿)：池id=" + request.getPondId() + "，批次" + batch.getBatchNo()
                        + "，病情观察单id=" + (observation == null ? "无" : observation.getId())
                        + "，水体" + request.getWaterVolumeM3() + "m³，合计估算"
                        + totalEstimated + "g，叠加最晚休药结束日" + latestEnd);
        return requisition;
    }

    /**
     * 提交：草稿/驳回态可提交。按 FEFO 为每个处方行预占未过期批号；
     * 批号过期或可用库存不足在此拦截，同事务回滚不残留半占用。提交后进入待审核，不扣库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public Requisition submit(Long id, RequisitionSubmitRequest request) {
        Requisition requisition = getOrThrow(id);
        if (Requisition.STATUS_SUBMITTED.equals(requisition.getStatus())
                || Requisition.STATUS_APPROVED.equals(requisition.getStatus())) {
            return requisition; // 幂等：重复提交不重复预占
        }
        if (Requisition.STATUS_REVOKED.equals(requisition.getStatus())) {
            throw new IllegalArgumentException("领用单已撤回，不能再提交：" + requisition.getRequisitionNo());
        }
        if (!Requisition.STATUS_DRAFT.equals(requisition.getStatus())
                && !Requisition.STATUS_REJECTED.equals(requisition.getStatus())) {
            throw new IllegalArgumentException("当前领用单状态不能提交：" + requisition.getStatus());
        }
        String operator = blankToDefault(request.getOperator(), requisition.getApplicant());

        // 先整体校验库存是否齐备：任一药品无未过期批号或库存不足，整体回滚不留半占用
        for (RequisitionItem item : listItems(id)) {
            batchService.allocate(item.getDrugId(), item.getEstimatedQuantityG(), requisition.getUseDate());
        }
        // 正式预占并落批号占用明细
        for (RequisitionItem item : listItems(id)) {
            List<DrugBatchService.Allocation> allocations =
                    batchService.allocate(item.getDrugId(), item.getEstimatedQuantityG(), requisition.getUseDate());
            for (DrugBatchService.Allocation allocation : allocations) {
                batchService.lockQuantity(allocation.getBatchId(), allocation.getQuantityG(),
                        operator, request.getRequestId());
                DrugBatchLock lock = new DrugBatchLock();
                lock.setDrugBatchId(allocation.getBatchId());
                lock.setRequisitionItemId(item.getId());
                lock.setQuantityG(allocation.getQuantityG());
                lock.setStatus(DrugBatchLock.STATUS_LOCKED);
                lockMapper.insert(lock);
            }
            item.setActualQuantityG(item.getEstimatedQuantityG());
            itemMapper.updateById(item);
        }

        // 提交即计算休药期并锁定预计出池日：多药叠加取最晚休药结束日。
        // 此时库存仅预占未扣减（待审核）；驳回/撤回会停用本锁定，审核通过则锁定转为确认。
        upsertActiveWithdrawalLock(requisition, operator, request.getRequestId(),
                "领用单提交：按叠加最晚休药结束日" + requisition.getWithdrawalEndDate()
                        + "锁定预计出池日（待审核，库存尚未扣减）");

        Requisition before = copyOf(requisition);
        requisition.setStatus(Requisition.STATUS_SUBMITTED);
        requisition.setSubmittedBy(operator);
        requisition.setSubmittedTime(LocalDateTime.now());
        requisitionMapper.updateById(requisition);
        evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, requisition.getId(),
                MedChangeEvidence.ACTION_SUBMIT, request.getRequestId(), operator, before, requisition,
                "领用单提交待审核：已按先到期先用预占批号库存（尚未扣减），休药期叠加最晚结束日"
                        + requisition.getWithdrawalEndDate() + "，待审核");
        return requisition;
    }

    /**
     * 审核：只有已提交(SUBMITTED)单可审核。
     * 通过：批号预占转实际消耗（库存此刻才扣减），并确认提交时已写入的休药期锁定；
     * 驳回：必须填意见，释放全部预占并停用休药期锁定。
     */
    @Transactional(rollbackFor = Exception.class)
    public Requisition review(Long id, RequisitionReviewRequest request) {
        Requisition requisition = getOrThrow(id);
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        if (approved && Requisition.STATUS_APPROVED.equals(requisition.getStatus())) {
            return requisition; // 幂等：重复通过不重复扣减
        }
        if (!Requisition.STATUS_SUBMITTED.equals(requisition.getStatus())) {
            throw new IllegalArgumentException("只有已提交待审核领用单才能审核，当前状态："
                    + requisition.getStatus());
        }
        Requisition before = copyOf(requisition);

        if (approved) {
            approve(requisition, request, before);
        } else {
            if (request.getRemark() == null || request.getRemark().trim().isEmpty()) {
                throw new IllegalArgumentException("驳回必须填写审核意见");
            }
            reject(requisition, request, before);
        }
        return requisition;
    }

    private void approve(Requisition requisition, RequisitionReviewRequest request, Requisition before) {
        for (RequisitionItem item : listItems(requisition.getId())) {
            List<DrugBatchLock> locks = activeLocks(item.getId());
            BigDecimal lockTotal = BigDecimal.ZERO;
            for (DrugBatchLock lock : locks) {
                lockTotal = lockTotal.add(lock.getQuantityG());
            }
            if (lockTotal.compareTo(item.getEstimatedQuantityG()) != 0) {
                throw new IllegalArgumentException("药品[" + item.getDrugName()
                        + "]预占" + lockTotal + "g与估算用量" + item.getEstimatedQuantityG()
                        + "g不一致，不能审核通过");
            }
            for (DrugBatchLock lock : locks) {
                DrugBatch batch = batchService.getOrThrow(lock.getDrugBatchId());
                if (batch.getExpiryDate().isBefore(requisition.getUseDate())) {
                    throw new IllegalArgumentException("药品批号" + batch.getBatchNo()
                            + "已于" + batch.getExpiryDate() + "过期，不能审核通过消耗");
                }
                batchService.consumeLocked(lock.getDrugBatchId(), lock.getQuantityG(),
                        request.getReviewer(), request.getRequestId());
                lock.setStatus(DrugBatchLock.STATUS_CONSUMED);
                lockMapper.updateById(lock);
            }
            item.setActualQuantityG(item.getEstimatedQuantityG());
            itemMapper.updateById(item);
        }

        // 休药期锁定在提交时已写入；审核通过将其确认为有效（库存扣减此刻才发生）。
        WithdrawalLock lock = withdrawalLockMapper.selectOne(new QueryWrapper<WithdrawalLock>()
                .eq("requisition_id", requisition.getId()));
        if (lock == null) {
            lock = buildActiveLock(requisition);
            withdrawalLockMapper.insert(lock);
        } else if (!WithdrawalLock.STATUS_ACTIVE.equals(lock.getStatus())) {
            lock.setStatus(WithdrawalLock.STATUS_ACTIVE);
            lock.setUseDate(requisition.getUseDate());
            lock.setWithdrawalEndDate(requisition.getWithdrawalEndDate());
            lock.setLockedHarvestDate(requisition.getLockedHarvestDate());
            withdrawalLockMapper.updateById(lock);
        }
        evidenceService.record(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, lock.getId(),
                MedChangeEvidence.ACTION_CREATE, request.getRequestId(), request.getReviewer(),
                null, lock,
                "审核通过确认休药期锁定：批次id=" + requisition.getBatchId()
                        + "，最晚休药结束日" + requisition.getWithdrawalEndDate()
                        + "，此前禁止出池；库存扣减已与审核保持一致");

        requisition.setStatus(Requisition.STATUS_APPROVED);
        requisition.setReviewedBy(request.getReviewer());
        requisition.setReviewedTime(LocalDateTime.now());
        requisition.setReviewRemark(request.getRemark());
        requisitionMapper.updateById(requisition);
        evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, requisition.getId(),
                MedChangeEvidence.ACTION_APPROVE, request.getRequestId(), request.getReviewer(),
                before, requisition,
                "领用审核通过：批号预占转为实际消耗（库存扣减与审核一致），审核人="
                        + request.getReviewer());
    }

    private void reject(Requisition requisition, RequisitionReviewRequest request, Requisition before) {
        releaseAllLocks(requisition, request.getReviewer(), request.getRequestId(), "审核驳回");
        deactivateWithdrawalLock(requisition, request.getReviewer(), request.getRequestId(),
                "审核驳回，停用提交时写入的休药期锁定并释放预占");
        requisition.setStatus(Requisition.STATUS_REJECTED);
        requisition.setReviewedBy(request.getReviewer());
        requisition.setReviewedTime(LocalDateTime.now());
        requisition.setReviewRemark(request.getRemark());
        requisitionMapper.updateById(requisition);
        evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, requisition.getId(),
                MedChangeEvidence.ACTION_REJECT, request.getRequestId(), request.getReviewer(),
                before, requisition,
                "领用审核驳回并释放全部预占库存：" + request.getRemark() + "，审核人=" + request.getReviewer());
    }

    /**
     * 撤回：只能撤销未审核单（草稿/已提交待审核）。已提交单先释放全部批号预占；
     * 已审核（通过/驳回）单不可撤回。revokeRequestId 幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public Requisition revoke(Long id, RequisitionRevokeRequest request) {
        Requisition requisition = getOrThrow(id);
        if (request.getRequestId() != null && request.getRequestId().equals(requisition.getRevokeRequestId())
                && Requisition.STATUS_REVOKED.equals(requisition.getStatus())) {
            return requisition; // 幂等：重复撤回返回首次结果
        }
        if (Requisition.STATUS_APPROVED.equals(requisition.getStatus())
                || Requisition.STATUS_REJECTED.equals(requisition.getStatus())) {
            throw new IllegalArgumentException("领用单已审核（" + requisition.getStatus()
                    + "），不能撤回；撤回只能撤销未审核单");
        }
        if (Requisition.STATUS_REVOKED.equals(requisition.getStatus())) {
            return requisition;
        }
        Requisition before = copyOf(requisition);
        if (Requisition.STATUS_SUBMITTED.equals(requisition.getStatus())) {
            releaseAllLocks(requisition, request.getOperator(), request.getRequestId(), "撤回未审核领用单");
            deactivateWithdrawalLock(requisition, request.getOperator(), request.getRequestId(),
                    "撤回未审核领用单，停用提交时写入的休药期锁定并释放预占");
        }
        requisition.setStatus(Requisition.STATUS_REVOKED);
        requisition.setRevokedBy(request.getOperator());
        requisition.setRevokedTime(LocalDateTime.now());
        requisition.setRevokeRequestId(request.getRequestId());
        requisitionMapper.updateById(requisition);
        evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, requisition.getId(),
                MedChangeEvidence.ACTION_REVOKE, request.getRequestId(), request.getOperator(),
                before, requisition,
                "撤回落销领用单（未审核）：操作人=" + request.getOperator()
                        + "，已释放全部批号预占，休药期锁定不生效");
        return requisition;
    }

    public Requisition getOrThrow(Long id) {
        Requisition requisition = requisitionMapper.selectById(id);
        if (requisition == null) {
            throw new IllegalArgumentException("用药领用单不存在，id=" + id);
        }
        return requisition;
    }

    public List<Requisition> list(Long pondId, Long batchId, String status) {
        QueryWrapper<Requisition> wrapper = new QueryWrapper<>();
        wrapper.eq(pondId != null, "pond_id", pondId);
        wrapper.eq(batchId != null, "batch_id", batchId);
        wrapper.eq(status != null && !status.trim().isEmpty(), "status", status);
        wrapper.orderByAsc("use_date", "id");
        return requisitionMapper.selectList(wrapper);
    }

    /**
     * 领用单明细，按角色返回不同视图。
     * VET 兽医：处方明细（药品/剂量/休药期/用量/病情）+ 批号占用与库存。
     * WAREHOUSE 仓管：只看库存字段（药品、批号、数量、批号三量账），不含剂量/休药期/病情等处方信息。
     */
    public Map<String, Object> detail(Long id, String role) {
        Requisition requisition = getOrThrow(id);
        if (ROLE_WAREHOUSE.equals(role)) {
            return warehouseDetail(requisition);
        }
        return vetDetail(requisition);
    }

    /** 列表按角色裁剪：仓管只给库存视角字段。 */
    public List<Map<String, Object>> listView(Long pondId, Long batchId, String status, String role) {
        List<Requisition> orders = list(pondId, batchId, status);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Requisition order : orders) {
            views.add(ROLE_WAREHOUSE.equals(role) ? warehouseHeader(order) : vetHeader(order));
        }
        return views;
    }

    // ------------------------------------------------------------
    // 角色视图
    // ------------------------------------------------------------

    private Map<String, Object> vetDetail(Requisition requisition) {
        Map<String, Object> detail = vetHeader(requisition);
        List<Map<String, Object>> itemPayload = new ArrayList<>();
        for (RequisitionItem item : listItems(requisition.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item);
            if (item.getObservationId() != null) {
                m.put("observation", observationService.getOrThrow(item.getObservationId()));
            }
            m.put("locks", locksWithBatch(item.getId()));
            itemPayload.add(m);
        }
        detail.put("items", itemPayload);
        if (requisition.getObservationId() != null) {
            detail.put("observation", observationService.getOrThrow(requisition.getObservationId()));
        }
        SeedlingBatch batch = seedlingBatchService.getOrThrow(requisition.getBatchId());
        Map<String, Object> harvest = new LinkedHashMap<>();
        harvest.put("plannedHarvestDate", batch.getPlannedHarvestDate());
        harvest.put("lockedHarvestDate", requisition.getLockedHarvestDate());
        harvest.put("earliestAllowedHarvestDate", requisition.getWithdrawalEndDate());
        detail.put("harvest", harvest);
        return detail;
    }

    private Map<String, Object> warehouseDetail(Requisition requisition) {
        Map<String, Object> detail = warehouseHeader(requisition);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RequisitionItem item : listItems(requisition.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 仓管只看库存字段：药名、应发/实发数量、批号分配与批号库存；不暴露剂量、休药期、病情
            row.put("drugId", item.getDrugId());
            row.put("drugName", item.getDrugName());
            row.put("estimatedQuantityG", item.getEstimatedQuantityG());
            row.put("actualQuantityG", item.getActualQuantityG());
            row.put("allocations", locksWithBatch(item.getId()));
            rows.add(row);
        }
        detail.put("items", rows);
        return detail;
    }

    private Map<String, Object> vetHeader(Requisition requisition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requisition", requisition);
        return m;
    }

    private Map<String, Object> warehouseHeader(Requisition requisition) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 仓管库存视角：单号、池、用药日、状态、应发总量；不含病情、剂量、休药期等处方字段
        m.put("requisitionNo", requisition.getRequisitionNo());
        m.put("pondId", requisition.getPondId());
        m.put("useDate", requisition.getUseDate());
        m.put("status", requisition.getStatus());
        m.put("totalEstimatedQuantityG", requisition.getEstimatedQuantityG());
        return m;
    }

    /** 批号占用 + 批号当前三量账，供两角色核对库存实物。 */
    private List<Map<String, Object>> locksWithBatch(Long itemId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DrugBatchLock lock : lockMapper.selectList(new QueryWrapper<DrugBatchLock>()
                .eq("requisition_item_id", itemId).orderByAsc("id"))) {
            DrugBatch batch = batchService.getOrThrow(lock.getDrugBatchId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lockId", lock.getId());
            m.put("drugBatchId", batch.getId());
            m.put("batchNo", batch.getBatchNo());
            m.put("quantityG", lock.getQuantityG());
            m.put("lockStatus", lock.getStatus());
            Map<String, Object> stock = new LinkedHashMap<>();
            stock.put("quantityIn", batch.getQuantityIn());
            stock.put("quantityLocked", batch.getQuantityLocked());
            stock.put("quantityConsumed", batch.getQuantityConsumed());
            stock.put("availableQuantityG", batch.availableQuantity());
            stock.put("expiryDate", batch.getExpiryDate());
            stock.put("status", batch.getStatus());
            m.put("batchStock", stock);
            result.add(m);
        }
        return result;
    }

    // ------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------

    /** 用量(g) = 剂量(mg/L) × 1000(L/m³) × 水体(m³) ÷ 1000(mg/g) = 剂量 × 水体。 */
    private BigDecimal estimateQuantityGram(BigDecimal dosageMgPerL, BigDecimal waterVolumeM3) {
        return dosageMgPerL.multiply(waterVolumeM3).setScale(2, RoundingMode.HALF_UP);
    }

    /** 提交时写入/重新激活休药期锁定（一领用单一行，唯一约束在 requisition_id）。 */
    private void upsertActiveWithdrawalLock(Requisition requisition, String operator,
                                            String requestId, String detail) {
        WithdrawalLock lock = withdrawalLockMapper.selectOne(new QueryWrapper<WithdrawalLock>()
                .eq("requisition_id", requisition.getId()));
        if (lock == null) {
            lock = buildActiveLock(requisition);
            withdrawalLockMapper.insert(lock);
        } else {
            lock.setStatus(WithdrawalLock.STATUS_ACTIVE);
            lock.setUseDate(requisition.getUseDate());
            lock.setWithdrawalEndDate(requisition.getWithdrawalEndDate());
            lock.setLockedHarvestDate(requisition.getLockedHarvestDate());
            withdrawalLockMapper.updateById(lock);
        }
        evidenceService.record(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, lock.getId(),
                MedChangeEvidence.ACTION_LOCK, requestId, operator, null, lock, detail);
    }

    /** 驳回/撤回：停用提交时写入的休药期锁定（只置状态，不删历史行）。 */
    private void deactivateWithdrawalLock(Requisition requisition, String operator,
                                          String requestId, String detail) {
        WithdrawalLock lock = withdrawalLockMapper.selectOne(new QueryWrapper<WithdrawalLock>()
                .eq("requisition_id", requisition.getId()));
        if (lock == null) {
            return;
        }
        if (WithdrawalLock.STATUS_ACTIVE.equals(lock.getStatus())) {
            lock.setStatus(WithdrawalLock.STATUS_REVOKED);
            withdrawalLockMapper.updateById(lock);
        }
        evidenceService.record(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, lock.getId(),
                MedChangeEvidence.ACTION_UNLOCK, requestId, operator, null, lock, detail);
    }

    private WithdrawalLock buildActiveLock(Requisition requisition) {
        WithdrawalLock lock = new WithdrawalLock();
        lock.setBatchId(requisition.getBatchId());
        lock.setPondId(requisition.getPondId());
        lock.setRequisitionId(requisition.getId());
        lock.setUseDate(requisition.getUseDate());
        lock.setWithdrawalEndDate(requisition.getWithdrawalEndDate());
        lock.setLockedHarvestDate(requisition.getLockedHarvestDate());
        lock.setStatus(WithdrawalLock.STATUS_ACTIVE);
        return lock;
    }

    private void releaseAllLocks(Requisition requisition, String operator, String requestId, String reason) {
        for (RequisitionItem item : listItems(requisition.getId())) {
            for (DrugBatchLock lock : lockMapper.selectList(new QueryWrapper<DrugBatchLock>()
                    .eq("requisition_item_id", item.getId())
                    .eq("status", DrugBatchLock.STATUS_LOCKED))) {
                batchService.releaseLocked(lock.getDrugBatchId(), lock.getQuantityG(),
                        operator, requestId, reason);
                lock.setStatus(DrugBatchLock.STATUS_RELEASED);
                lockMapper.updateById(lock);
            }
            item.setActualQuantityG(null);
            itemMapper.updateById(item);
        }
    }

    private List<DrugBatchLock> activeLocks(Long itemId) {
        return lockMapper.selectList(new QueryWrapper<DrugBatchLock>()
                .eq("requisition_item_id", itemId)
                .eq("status", DrugBatchLock.STATUS_LOCKED)
                .orderByAsc("id"));
    }

    private List<RequisitionItem> listItems(Long requisitionId) {
        return itemMapper.selectList(new QueryWrapper<RequisitionItem>()
                .eq("requisition_id", requisitionId).orderByAsc("id"));
    }

    private SeedlingBatch requireBatchInPond(Long batchId, Long pondId) {
        SeedlingBatch batch = seedlingBatchService.getOrThrow(batchId);
        seedlingBatchService.requireStocked(batch, "登记用药领用");
        if (!batch.getPondId().equals(pondId)) {
            throw new IllegalArgumentException(
                    "批次" + batch.getBatchNo() + "当前不在池塘id=" + pondId + "，请核对或先登记换池");
        }
        Pond pond = pondService.getOrThrow(pondId);
        pondService.requireActive(pond);
        return batch;
    }

    private DiseaseObservation requireObservation(Long observationId, Long pondId, Long batchId) {
        DiseaseObservation observation = observationService.getOrThrow(observationId);
        if (!observation.getPondId().equals(pondId) || !observation.getBatchId().equals(batchId)) {
            throw new IllegalArgumentException("病情观察单id=" + observationId
                    + "不属于池塘id=" + pondId + "批次id=" + batchId + "，不能关联到本领用单");
        }
        return observation;
    }

    private String nextRequisitionNo(Long batchId, LocalDate useDate) {
        Long count = requisitionMapper.selectCount(new QueryWrapper<Requisition>()
                .eq("batch_id", batchId).eq("use_date", useDate));
        long seq = (count == null ? 0 : count) + 1;
        return "MED-" + batchId + "-" + useDate.toString().replace("-", "") + "-" + seq;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private Requisition copyOf(Requisition source) {
        Requisition copy = new Requisition();
        copy.setId(source.getId());
        copy.setRequisitionNo(source.getRequisitionNo());
        copy.setPondId(source.getPondId());
        copy.setBatchId(source.getBatchId());
        copy.setObservationId(source.getObservationId());
        copy.setUseDate(source.getUseDate());
        copy.setWaterVolumeM3(source.getWaterVolumeM3());
        copy.setEstimatedQuantityG(source.getEstimatedQuantityG());
        copy.setWithdrawalEndDate(source.getWithdrawalEndDate());
        copy.setLockedHarvestDate(source.getLockedHarvestDate());
        copy.setStatus(source.getStatus());
        copy.setApplicant(source.getApplicant());
        copy.setSubmittedBy(source.getSubmittedBy());
        copy.setSubmittedTime(source.getSubmittedTime());
        copy.setReviewedBy(source.getReviewedBy());
        copy.setReviewedTime(source.getReviewedTime());
        copy.setReviewRemark(source.getReviewRemark());
        copy.setRevokedBy(source.getRevokedBy());
        copy.setRevokedTime(source.getRevokedTime());
        copy.setRevokeRequestId(source.getRevokeRequestId());
        copy.setRequestId(source.getRequestId());
        copy.setRemark(source.getRemark());
        return copy;
    }
}
