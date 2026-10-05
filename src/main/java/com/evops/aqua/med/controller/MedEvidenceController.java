package com.evops.aqua.med.controller;

import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.service.MedEvidenceService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用药模块变更证据查询：按对象或 requestId 追溯药品库存与领用单全链路。
 */
@RestController
@RequestMapping("/api/aqua/med/evidence")
public class MedEvidenceController {

    private final MedEvidenceService evidenceService;

    public MedEvidenceController(MedEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping
    public ApiResponse<List<MedChangeEvidence>> list(@RequestParam(required = false) String entityType,
                                                     @RequestParam(required = false) Long entityId,
                                                     @RequestParam(required = false) String requestId) {
        return ApiResponse.ok(evidenceService.list(entityType, entityId, requestId));
    }
}
