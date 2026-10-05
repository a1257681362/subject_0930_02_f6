package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用药领用单：关联池塘、投苗批次与病情（疫病观察单）。
 * 状态 DRAFT（草稿）/ SUBMITTED（已提交待审核）/ APPROVED（已审核）/ REJECTED（已驳回）/ REVOKED（已撤回）。
 * 提交即按 FEFO 预占药品批号、按剂量×水体估算用量、计算休药期并锁定预计出池日；
 * 不同药品叠加时休药结束日取最晚者。审核通过才把预占转为实际消耗。
 */
@TableName("t_med_requisition")
public class Requisition extends BaseEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";

    private String requisitionNo;
    private Long pondId;
    private Long batchId;
    private Long observationId;
    private LocalDate useDate;
    private BigDecimal waterVolumeM3;
    private BigDecimal estimatedQuantityG;
    private LocalDate withdrawalEndDate;
    private LocalDate lockedHarvestDate;
    private String status;
    private String applicant;
    private String submittedBy;
    private LocalDateTime submittedTime;
    private String reviewedBy;
    private LocalDateTime reviewedTime;
    private String reviewRemark;
    private String revokedBy;
    private LocalDateTime revokedTime;
    private String revokeRequestId;
    private String requestId;
    private String remark;

    public String getRequisitionNo() { return requisitionNo; }
    public void setRequisitionNo(String requisitionNo) { this.requisitionNo = requisitionNo; }
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
    public BigDecimal getEstimatedQuantityG() { return estimatedQuantityG; }
    public void setEstimatedQuantityG(BigDecimal estimatedQuantityG) { this.estimatedQuantityG = estimatedQuantityG; }
    public LocalDate getWithdrawalEndDate() { return withdrawalEndDate; }
    public void setWithdrawalEndDate(LocalDate withdrawalEndDate) { this.withdrawalEndDate = withdrawalEndDate; }
    public LocalDate getLockedHarvestDate() { return lockedHarvestDate; }
    public void setLockedHarvestDate(LocalDate lockedHarvestDate) { this.lockedHarvestDate = lockedHarvestDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApplicant() { return applicant; }
    public void setApplicant(String applicant) { this.applicant = applicant; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public LocalDateTime getSubmittedTime() { return submittedTime; }
    public void setSubmittedTime(LocalDateTime submittedTime) { this.submittedTime = submittedTime; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedTime() { return reviewedTime; }
    public void setReviewedTime(LocalDateTime reviewedTime) { this.reviewedTime = reviewedTime; }
    public String getReviewRemark() { return reviewRemark; }
    public void setReviewRemark(String remark) { this.reviewRemark = remark; }
    public String getRevokedBy() { return revokedBy; }
    public void setRevokedBy(String revokedBy) { this.revokedBy = revokedBy; }
    public LocalDateTime getRevokedTime() { return revokedTime; }
    public void setRevokedTime(LocalDateTime revokedTime) { this.revokedTime = revokedTime; }
    public String getRevokeRequestId() { return revokeRequestId; }
    public void setRevokeRequestId(String revokeRequestId) { this.revokeRequestId = revokeRequestId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
