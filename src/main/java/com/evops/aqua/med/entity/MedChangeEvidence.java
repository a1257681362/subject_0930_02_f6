package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 用药模块变更证据：所有修改留痕（对象、动作、操作前后 JSON 快照、幂等请求号、操作者）。
 * 只追加不修改不删除。
 */
@TableName("t_med_change_evidence")
public class MedChangeEvidence extends BaseEntity {

    public static final String ENTITY_DRUG = "DRUG";
    public static final String ENTITY_DRUG_BATCH = "DRUG_BATCH";
    public static final String ENTITY_REQUISITION = "REQUISITION";
    public static final String ENTITY_REQUISITION_ITEM = "REQUISITION_ITEM";
    public static final String ENTITY_WITHDRAWAL_LOCK = "WITHDRAWAL_LOCK";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_SUBMIT = "SUBMIT";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_REVOKE = "REVOKE";
    public static final String ACTION_LOCK = "LOCK";
    public static final String ACTION_UNLOCK = "UNLOCK";
    public static final String ACTION_CONSUME = "CONSUME";
    public static final String ACTION_HARVEST_BLOCKED = "HARVEST_BLOCKED";

    private String entityType;
    private Long entityId;
    private String action;
    private String requestId;
    private String operator;
    private String beforeJson;
    private String afterJson;
    private String detail;

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }
    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
