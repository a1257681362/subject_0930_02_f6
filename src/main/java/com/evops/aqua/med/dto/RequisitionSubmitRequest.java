package com.evops.aqua.med.dto;

/**
 * 领用单提交请求：提交后进入待审核（SUBMITTED），按 FEFO 预占批号并计算休药期。
 * requestId 用于幂等与留证；operator 为提交人，为空沿用领用人。
 */
public class RequisitionSubmitRequest {

    private String requestId;
    private String operator;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
