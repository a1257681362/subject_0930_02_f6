package com.evops.aqua.med.controller;

import com.evops.aqua.med.dto.RequisitionCreateRequest;
import com.evops.aqua.med.dto.RequisitionReviewRequest;
import com.evops.aqua.med.dto.RequisitionRevokeRequest;
import com.evops.aqua.med.dto.RequisitionSubmitRequest;
import com.evops.aqua.med.entity.Requisition;
import com.evops.aqua.med.service.RequisitionService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用药领用单：建单(草稿，按剂量×水体估算) → 提交(FEFO预占批号、计算休药期)
 * → 审核(通过才扣库存并锁定出池日/驳回释放) → 撤回(仅未审核单)。
 * role=VET 兽医看处方明细；role=WAREHOUSE 仓管只看库存字段。
 */
@RestController
@RequestMapping("/api/aqua/med/requisitions")
public class RequisitionController {

    private final RequisitionService requisitionService;

    public RequisitionController(RequisitionService requisitionService) {
        this.requisitionService = requisitionService;
    }

    @PostMapping
    public ApiResponse<Requisition> create(@Validated @RequestBody RequisitionCreateRequest request) {
        return ApiResponse.ok(requisitionService.create(request));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Requisition> submit(@PathVariable Long id,
                                           @RequestBody(required = false) RequisitionSubmitRequest request) {
        return ApiResponse.ok(requisitionService.submit(id,
                request == null ? new RequisitionSubmitRequest() : request));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<Requisition> review(@PathVariable Long id,
                                           @Validated @RequestBody RequisitionReviewRequest request) {
        return ApiResponse.ok(requisitionService.review(id, request));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<Requisition> revoke(@PathVariable Long id,
                                           @Validated @RequestBody RequisitionRevokeRequest request) {
        return ApiResponse.ok(requisitionService.revoke(id, request));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long pondId,
                                                       @RequestParam(required = false) Long batchId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String role) {
        return ApiResponse.ok(requisitionService.listView(pondId, batchId, status, role));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id,
                                                   @RequestParam(required = false) String role) {
        return ApiResponse.ok(requisitionService.detail(id, role));
    }
}
