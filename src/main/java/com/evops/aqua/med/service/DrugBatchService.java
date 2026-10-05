package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.med.dto.DrugBatchStockRequest;
import com.evops.aqua.med.entity.Drug;
import com.evops.aqua.med.entity.DrugBatch;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.mapper.DrugBatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 药品批号：入库登记与三量账（入库/锁定占用/已消耗），单位 g。
 * 领用单提交时按 FEFO（先到期先用）从未过期、库存充足的批号分配占用；
 * 批号过期或可用库存不足一律拦截，且同事务回滚不残留半占用。
 * 只有审核通过才把锁定占用转为实际消耗（库存扣减与领用审核保持一致）。
 */
@Service
public class DrugBatchService {

    private final DrugBatchMapper batchMapper;
    private final DrugService drugService;
    private final MedEvidenceService evidenceService;

    public DrugBatchService(DrugBatchMapper batchMapper,
                            DrugService drugService,
                            MedEvidenceService evidenceService) {
        this.batchMapper = batchMapper;
        this.drugService = drugService;
        this.evidenceService = evidenceService;
    }

    /** 批号入库。 */
    @Transactional(rollbackFor = Exception.class)
    public DrugBatch stock(DrugBatchStockRequest request) {
        Drug drug = drugService.getOrThrow(request.getDrugId());
        Long duplicated = batchMapper.selectCount(
                new QueryWrapper<DrugBatch>().eq("batch_no", request.getBatchNo()));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("药品批号已存在：" + request.getBatchNo());
        }
        if (request.getProductionDate() != null
                && request.getExpiryDate().isBefore(request.getProductionDate())) {
            throw new IllegalArgumentException("到期日期不能早于生产日期");
        }
        DrugBatch batch = new DrugBatch();
        batch.setBatchNo(request.getBatchNo());
        batch.setDrugId(drug.getId());
        batch.setDrugName(drug.getDrugName());
        batch.setSupplier(request.getSupplier());
        batch.setQuantityIn(request.getQuantityIn());
        batch.setQuantityLocked(BigDecimal.ZERO);
        batch.setQuantityConsumed(BigDecimal.ZERO);
        batch.setProductionDate(request.getProductionDate());
        batch.setExpiryDate(request.getExpiryDate());
        batch.setStatus(DrugBatch.STATUS_IN_STOCK);
        batch.setRemark(request.getRemark());
        batchMapper.insert(batch);
        evidenceService.record(MedChangeEvidence.ENTITY_DRUG_BATCH, batch.getId(),
                MedChangeEvidence.ACTION_CREATE, null, request.getOperator(), null, batch,
                "药品批号入库：" + batch.getDrugName() + " " + batch.getQuantityIn() + "g，到期日"
                        + batch.getExpiryDate() + "，经办人=" + request.getOperator());
        return batch;
    }

    public DrugBatch getOrThrow(Long id) {
        DrugBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new IllegalArgumentException("药品批号不存在，id=" + id);
        }
        return batch;
    }

    public List<DrugBatch> list(Long drugId, String drugName) {
        QueryWrapper<DrugBatch> wrapper = new QueryWrapper<>();
        wrapper.eq(drugId != null, "drug_id", drugId);
        wrapper.eq(drugName != null && !drugName.trim().isEmpty(), "drug_name", drugName);
        wrapper.orderByAsc("expiry_date", "id");
        return batchMapper.selectList(wrapper);
    }

    /**
     * 按 FEFO 为某药品分配所需数量：优先占用最早到期的可用批号，可跨批号拼配。
     * 只取未过期（到期日 &gt;= 用药日）且仍有可用量的批号；全部过期或可用量不足时拦截。
     */
    public List<Allocation> allocate(Long drugId, BigDecimal needQty, LocalDate useDate) {
        List<DrugBatch> candidates = batchMapper.selectList(new QueryWrapper<DrugBatch>()
                .eq("drug_id", drugId)
                .ge("expiry_date", useDate)
                .orderByAsc("expiry_date", "id"));
        List<DrugBatch> usable = new ArrayList<>();
        BigDecimal totalAvailable = BigDecimal.ZERO;
        for (DrugBatch batch : candidates) {
            if (batch.availableQuantity().compareTo(BigDecimal.ZERO) > 0) {
                usable.add(batch);
                totalAvailable = totalAvailable.add(batch.availableQuantity());
            }
        }
        Drug drug = drugService.getOrThrow(drugId);
        if (usable.isEmpty()) {
            throw new IllegalArgumentException(
                    "药品[" + drug.getDrugName() + "]在" + useDate + "无未过期且有库存的批号可用");
        }
        if (totalAvailable.compareTo(needQty) < 0) {
            throw new IllegalArgumentException(
                    "药品[" + drug.getDrugName() + "]可用库存不足：需要" + needQty + "g，未过期批号合计可用"
                            + totalAvailable + "g");
        }
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remain = needQty;
        for (DrugBatch batch : usable) {
            if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal take = batch.availableQuantity().min(remain);
            if (take.compareTo(BigDecimal.ZERO) > 0) {
                allocations.add(new Allocation(batch.getId(), take));
                remain = remain.subtract(take);
            }
        }
        return allocations;
    }

    /** 领用单提交：预占批号库存（locked += qty）。 */
    @Transactional(rollbackFor = Exception.class)
    public void lockQuantity(Long batchId, BigDecimal qty, String operator, String requestId) {
        DrugBatch before = copyOf(getOrThrow(batchId));
        DrugBatch batch = getOrThrow(batchId);
        if (batch.availableQuantity().compareTo(qty) < 0) {
            throw new IllegalArgumentException("药品批号" + batch.getBatchNo()
                    + "可用库存不足：需要" + qty + "g，可用" + batch.availableQuantity() + "g");
        }
        batch.setQuantityLocked(batch.getQuantityLocked().add(qty));
        batchMapper.updateById(batch);
        evidenceService.record(MedChangeEvidence.ENTITY_DRUG_BATCH, batch.getId(),
                MedChangeEvidence.ACTION_LOCK, requestId, operator, before, batch,
                "领用单提交预占" + qty + "g，占用后可用" + batch.availableQuantity() + "g");
    }

    /** 撤回/驳回：释放已预占但未实际使用的数量（locked -= qty）。 */
    @Transactional(rollbackFor = Exception.class)
    public void releaseLocked(Long batchId, BigDecimal qty, String operator, String requestId, String reason) {
        DrugBatch before = copyOf(getOrThrow(batchId));
        DrugBatch batch = getOrThrow(batchId);
        if (batch.getQuantityLocked().compareTo(qty) < 0) {
            throw new IllegalArgumentException("药品批号" + batch.getBatchNo()
                    + "锁定占用量不足，无法释放" + qty + "g，当前占用" + batch.getQuantityLocked() + "g");
        }
        batch.setQuantityLocked(batch.getQuantityLocked().subtract(qty));
        if (DrugBatch.STATUS_EXHAUSTED.equals(batch.getStatus())
                && batch.availableQuantity().compareTo(BigDecimal.ZERO) > 0) {
            batch.setStatus(DrugBatch.STATUS_IN_STOCK);
        }
        batchMapper.updateById(batch);
        evidenceService.record(MedChangeEvidence.ENTITY_DRUG_BATCH, batch.getId(),
                MedChangeEvidence.ACTION_UNLOCK, requestId, operator, before, batch,
                reason + "：释放预占" + qty + "g，释放后可用" + batch.availableQuantity() + "g");
    }

    /**
     * 领用单审核通过：把预占转为实际消耗（locked -= qty, consumed += qty）。
     * 库存扣减只在审核通过这一刻发生，与领用审核保持一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void consumeLocked(Long batchId, BigDecimal qty, String operator, String requestId) {
        DrugBatch before = copyOf(getOrThrow(batchId));
        DrugBatch batch = getOrThrow(batchId);
        if (batch.getQuantityLocked().compareTo(qty) < 0) {
            throw new IllegalArgumentException("药品批号" + batch.getBatchNo()
                    + "锁定占用量不足，无法消耗" + qty + "g，当前占用" + batch.getQuantityLocked() + "g");
        }
        batch.setQuantityLocked(batch.getQuantityLocked().subtract(qty));
        batch.setQuantityConsumed(batch.getQuantityConsumed().add(qty));
        if (batch.availableQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(DrugBatch.STATUS_EXHAUSTED);
        }
        batchMapper.updateById(batch);
        evidenceService.record(MedChangeEvidence.ENTITY_DRUG_BATCH, batch.getId(),
                MedChangeEvidence.ACTION_CONSUME, requestId, operator, before, batch,
                "领用审核通过，预占转实际消耗" + qty + "g，累计消耗" + batch.getQuantityConsumed()
                        + "g，审核人=" + operator);
    }

    private DrugBatch copyOf(DrugBatch source) {
        DrugBatch copy = new DrugBatch();
        copy.setId(source.getId());
        copy.setBatchNo(source.getBatchNo());
        copy.setDrugId(source.getDrugId());
        copy.setDrugName(source.getDrugName());
        copy.setSupplier(source.getSupplier());
        copy.setQuantityIn(source.getQuantityIn());
        copy.setQuantityLocked(source.getQuantityLocked());
        copy.setQuantityConsumed(source.getQuantityConsumed());
        copy.setProductionDate(source.getProductionDate());
        copy.setExpiryDate(source.getExpiryDate());
        copy.setStatus(source.getStatus());
        copy.setRemark(source.getRemark());
        return copy;
    }

    /** 一条批号分配：批号 id + 本次占用数量。 */
    public static class Allocation {
        private final Long batchId;
        private final BigDecimal quantityG;

        public Allocation(Long batchId, BigDecimal quantityG) {
            this.batchId = batchId;
            this.quantityG = quantityG;
        }

        public Long getBatchId() { return batchId; }
        public BigDecimal getQuantityG() { return quantityG; }
    }
}
