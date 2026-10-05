package com.evops.aqua.med.controller;

import com.evops.aqua.med.dto.DrugBatchStockRequest;
import com.evops.aqua.med.entity.DrugBatch;
import com.evops.aqua.med.service.DrugBatchService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 药品批号：入库登记（三量账）、库存查询。库存扣减只在领用审核通过时发生。
 */
@RestController
@RequestMapping("/api/aqua/med/drug-batches")
public class DrugBatchController {

    private final DrugBatchService batchService;

    public DrugBatchController(DrugBatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping
    public ApiResponse<DrugBatch> stock(@Validated @RequestBody DrugBatchStockRequest request) {
        return ApiResponse.ok(batchService.stock(request));
    }

    @GetMapping
    public ApiResponse<List<DrugBatch>> list(@RequestParam(required = false) Long drugId,
                                             @RequestParam(required = false) String drugName) {
        return ApiResponse.ok(batchService.list(drugId, drugName));
    }
}
