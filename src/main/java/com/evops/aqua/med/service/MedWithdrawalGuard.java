package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.entity.WithdrawalLock;
import com.evops.aqua.med.mapper.WithdrawalLockMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 休药期出池守卫：供台账（出池）调用的只读校验。
 * 只依赖本模块 Mapper 与证据服务，不反向依赖台账 Service，避免循环依赖。
 * 批次有效休药期锁定中最晚结束日 = 允许最早出池日；出池日早于该日一律拦截。
 */
@Component
public class MedWithdrawalGuard {

    private final WithdrawalLockMapper lockMapper;
    private final MedEvidenceService evidenceService;

    public MedWithdrawalGuard(WithdrawalLockMapper lockMapper, MedEvidenceService evidenceService) {
        this.lockMapper = lockMapper;
        this.evidenceService = evidenceService;
    }

    /** 批次当前有效休药期锁定中最晚的休药结束日；无有效锁定返回 null。 */
    public LocalDate earliestHarvestDate(Long batchId) {
        List<WithdrawalLock> active = lockMapper.selectList(new QueryWrapper<WithdrawalLock>()
                .eq("batch_id", batchId)
                .eq("status", WithdrawalLock.STATUS_ACTIVE)
                .orderByDesc("withdrawal_end_date"));
        return active.isEmpty() ? null : active.get(0).getWithdrawalEndDate();
    }

    /**
     * 出池前校验：预计/实际出池日不得早于最晚休药结束日。
     * 被拦截的出池尝试同样独立事务留证（HARVEST_BLOCKED），可追溯。
     */
    public void requireHarvestAfterWithdrawal(Long batchId, LocalDate harvestDate, String operator) {
        LocalDate earliest = earliestHarvestDate(batchId);
        if (earliest != null && harvestDate.isBefore(earliest)) {
            String detail = "批次id=" + batchId + "拟于" + harvestDate + "出池，早于最晚休药结束日"
                    + earliest + "，休药期未满，禁止出池";
            evidenceService.recordStandalone(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, batchId,
                    MedChangeEvidence.ACTION_HARVEST_BLOCKED, null,
                    operator == null ? "台账出池" : operator, null, null, detail);
            throw new IllegalArgumentException(detail + "；最早允许出池日=" + earliest);
        }
    }
}
