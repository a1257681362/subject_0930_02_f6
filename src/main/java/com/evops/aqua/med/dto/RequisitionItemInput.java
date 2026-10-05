package com.evops.aqua.med.dto;

import javax.validation.constraints.NotNull;

/**
 * 领用单处方行输入：指定药品；行级病情（疫病观察单）可空，为空时沿用领用单抬头病情。
 */
public class RequisitionItemInput {

    @NotNull(message = "处方药品不能为空")
    private Long drugId;

    /** 该行针对的病情（疫病观察单）；可空，空则取领用单抬头 observationId。 */
    private Long observationId;

    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
}
