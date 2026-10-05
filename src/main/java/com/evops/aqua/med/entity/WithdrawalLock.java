package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDate;

/**
 * 批次休药期锁定：领用单审核通过后按池批次写一条有效锁定，只追加。
 * 撤回领用单置 REVOKED 不删历史；有效锁定取最晚休药结束日，预计出池日早于该日一律拦截。
 */
@TableName("t_med_withdrawal_lock")
public class WithdrawalLock extends BaseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long batchId;
    private Long pondId;
    private Long requisitionId;
    private LocalDate useDate;
    private LocalDate withdrawalEndDate;
    private LocalDate lockedHarvestDate;
    private String status;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public Long getRequisitionId() { return requisitionId; }
    public void setRequisitionId(Long requisitionId) { this.requisitionId = requisitionId; }
    public LocalDate getUseDate() { return useDate; }
    public void setUseDate(LocalDate useDate) { this.useDate = useDate; }
    public LocalDate getWithdrawalEndDate() { return withdrawalEndDate; }
    public void setWithdrawalEndDate(LocalDate withdrawalEndDate) { this.withdrawalEndDate = withdrawalEndDate; }
    public LocalDate getLockedHarvestDate() { return lockedHarvestDate; }
    public void setLockedHarvestDate(LocalDate lockedHarvestDate) { this.lockedHarvestDate = lockedHarvestDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
