package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 药品批号占用明细：领用单提交时为每个领用明细对具体批号加锁，
 * 审核通过转为实际消耗，撤回（或驳回）则释放。
 */
@TableName("t_med_drug_batch_lock")
public class DrugBatchLock extends BaseEntity {

    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_RELEASED = "RELEASED";

    private Long drugBatchId;
    private Long requisitionItemId;
    private BigDecimal quantityG;
    private String status;

    public Long getDrugBatchId() { return drugBatchId; }
    public void setDrugBatchId(Long drugBatchId) { this.drugBatchId = drugBatchId; }
    public Long getRequisitionItemId() { return requisitionItemId; }
    public void setRequisitionItemId(Long requisitionItemId) { this.requisitionItemId = requisitionItemId; }
    public BigDecimal getQuantityG() { return quantityG; }
    public void setQuantityG(BigDecimal quantityG) { this.quantityG = quantityG; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
