package com.evops.aqua.med.controller;

import com.evops.aqua.med.dto.DrugCreateRequest;
import com.evops.aqua.med.entity.Drug;
import com.evops.aqua.med.service.DrugService;
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
 * 药品目录：登记药品（休药期+泼洒剂量），供领用单估算用量与休药期。
 */
@RestController
@RequestMapping("/api/aqua/med/drugs")
public class DrugController {

    private final DrugService drugService;

    public DrugController(DrugService drugService) {
        this.drugService = drugService;
    }

    @PostMapping
    public ApiResponse<Drug> register(@Validated @RequestBody DrugCreateRequest request) {
        return ApiResponse.ok(drugService.register(request));
    }

    @GetMapping
    public ApiResponse<List<Drug>> list(@RequestParam(required = false) String drugName) {
        return ApiResponse.ok(drugService.list(drugName));
    }
}
