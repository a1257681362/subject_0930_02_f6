package com.evops.aqua.med.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 领用单审核请求：只有已提交(SUBMITTED)单可审核。
 * 通过：批号预占转实际消耗并写休药期锁定；驳回：必须填写审核意见并释放预占。
 */
public class RequisitionReviewRequest {

    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    @NotBlank(message = "审核人不能为空")
    private String reviewer;

    /** 驳回时必填。 */
    private String remark;

    private String requestId;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
