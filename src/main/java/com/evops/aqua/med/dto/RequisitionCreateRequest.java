package com.evops.aqua.med.dto;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 用药领用单创建请求：关联池塘、投苗批次与病情（疫病观察单），按剂量×水体估算用量。
 * 建单为草稿 DRAFT，即冻结各药品的剂量/休药期快照与估算用量；提交时才预占批号库存。
 */
public class RequisitionCreateRequest {

    @NotNull(message = "养殖池不能为空")
    private Long pondId;

    @NotNull(message = "投苗批次不能为空")
    private Long batchId;

    /** 抬头关联病情（疫病观察单）；处方行未单独指定病情时沿用此值。 */
    private Long observationId;

    @NotNull(message = "用药日期不能为空")
    private LocalDate useDate;

    @NotNull(message = "水体体积(m³)不能为空")
    @DecimalMin(value = "0.01", message = "水体体积必须大于0")
    private BigDecimal waterVolumeM3;

    @NotBlank(message = "领用人(兽医/申请人)不能为空")
    private String applicant;

    @NotEmpty(message = "领用单至少要有一条处方药品明细")
    @Valid
    private List<RequisitionItemInput> items;

    private String requestId;

    private String remark;

    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
    public LocalDate getUseDate() { return useDate; }
    public void setUseDate(LocalDate useDate) { this.useDate = useDate; }
    public BigDecimal getWaterVolumeM3() { return waterVolumeM3; }
    public void setWaterVolumeM3(BigDecimal waterVolumeM3) { this.waterVolumeM3 = waterVolumeM3; }
    public String getApplicant() { return applicant; }
    public void setApplicant(String applicant) { this.applicant = applicant; }
    public List<RequisitionItemInput> getItems() { return items; }
    public void setItems(List<RequisitionItemInput> items) { this.items = items; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
