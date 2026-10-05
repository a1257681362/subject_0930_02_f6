package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 领用单明细（处方行）：关联药品与病情，按剂量×水体估算用量。
 * 提交时冻结药品名、剂量、休药期、估算用量与休药结束日快照，此后不随药品目录变更而变。
 */
@TableName("t_med_requisition_item")
public class RequisitionItem extends BaseEntity {

    private Long requisitionId;
    private Long drugId;
    private String drugName;
    private Long observationId;
    private BigDecimal dosageMgPerL;
    private Integer withdrawalDays;
    private BigDecimal estimatedQuantityG;
    private BigDecimal actualQuantityG;
    private LocalDate withdrawalEndDate;

    public Long getRequisitionId() { return requisitionId; }
    public void setRequisitionId(Long requisitionId) { this.requisitionId = requisitionId; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
    public BigDecimal getDosageMgPerL() { return dosageMgPerL; }
    public void setDosageMgPerL(BigDecimal dosageMgPerL) { this.dosageMgPerL = dosageMgPerL; }
    public Integer getWithdrawalDays() { return withdrawalDays; }
    public void setWithdrawalDays(Integer withdrawalDays) { this.withdrawalDays = withdrawalDays; }
    public BigDecimal getEstimatedQuantityG() { return estimatedQuantityG; }
    public void setEstimatedQuantityG(BigDecimal estimatedQuantityG) { this.estimatedQuantityG = estimatedQuantityG; }
    public BigDecimal getActualQuantityG() { return actualQuantityG; }
    public void setActualQuantityG(BigDecimal actualQuantityG) { this.actualQuantityG = actualQuantityG; }
    public LocalDate getWithdrawalEndDate() { return withdrawalEndDate; }
    public void setWithdrawalEndDate(LocalDate withdrawalEndDate) { this.withdrawalEndDate = withdrawalEndDate; }
}
