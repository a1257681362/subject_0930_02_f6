package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 药品批号：一批药品一行，三量账。
 * 可用量(g) = quantityIn - quantityLocked - quantityConsumed。
 * 批号不可删除；过期或可用库存不足时禁止再被领用单占用。
 */
@TableName("t_med_drug_batch")
public class DrugBatch extends BaseEntity {

    public static final String STATUS_IN_STOCK = "IN_STOCK";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";

    private String batchNo;
    private Long drugId;
    private String drugName;
    private String supplier;
    private BigDecimal quantityIn;
    private BigDecimal quantityLocked;
    private BigDecimal quantityConsumed;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private String status;
    private String remark;

    /** 可用库存(g) = 入库 - 锁定占用 - 已消耗。 */
    public BigDecimal availableQuantity() {
        BigDecimal locked = quantityLocked == null ? BigDecimal.ZERO : quantityLocked;
        BigDecimal consumed = quantityConsumed == null ? BigDecimal.ZERO : quantityConsumed;
        return quantityIn.subtract(locked).subtract(consumed);
    }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public BigDecimal getQuantityIn() { return quantityIn; }
    public void setQuantityIn(BigDecimal quantityIn) { this.quantityIn = quantityIn; }
    public BigDecimal getQuantityLocked() { return quantityLocked; }
    public void setQuantityLocked(BigDecimal quantityLocked) { this.quantityLocked = quantityLocked; }
    public BigDecimal getQuantityConsumed() { return quantityConsumed; }
    public void setQuantityConsumed(BigDecimal quantityConsumed) { this.quantityConsumed = quantityConsumed; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
