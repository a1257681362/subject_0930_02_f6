package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.med.dto.DrugCreateRequest;
import com.evops.aqua.med.entity.Drug;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.mapper.DrugMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 药品目录：登记药品（携带休药期与泼洒剂量），为领用单估算用量与休药期提供主数据。
 */
@Service
public class DrugService {

    private final DrugMapper drugMapper;
    private final MedEvidenceService evidenceService;

    public DrugService(DrugMapper drugMapper, MedEvidenceService evidenceService) {
        this.drugMapper = drugMapper;
        this.evidenceService = evidenceService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Drug register(DrugCreateRequest request) {
        Long duplicated = drugMapper.selectCount(
                new QueryWrapper<Drug>().eq("drug_code", request.getDrugCode()));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("药品编码已存在：" + request.getDrugCode());
        }
        Drug drug = new Drug();
        drug.setDrugCode(request.getDrugCode());
        drug.setDrugName(request.getDrugName());
        drug.setDosageMgPerL(request.getDosageMgPerL());
        drug.setWithdrawalDays(request.getWithdrawalDays());
        drug.setManufacturer(request.getManufacturer());
        drug.setStatus(Drug.STATUS_ACTIVE);
        drug.setRemark(request.getRemark());
        drugMapper.insert(drug);
        evidenceService.record(MedChangeEvidence.ENTITY_DRUG, drug.getId(),
                MedChangeEvidence.ACTION_CREATE, null, request.getOperator(), null, drug,
                "登记药品：" + drug.getDrugName() + "，剂量" + drug.getDosageMgPerL()
                        + "mg/L，休药期" + drug.getWithdrawalDays() + "天");
        return drug;
    }

    public Drug getOrThrow(Long id) {
        Drug drug = drugMapper.selectById(id);
        if (drug == null) {
            throw new IllegalArgumentException("药品不存在，id=" + id);
        }
        return drug;
    }

    public List<Drug> list(String drugName) {
        QueryWrapper<Drug> wrapper = new QueryWrapper<>();
        wrapper.eq(drugName != null && !drugName.trim().isEmpty(), "drug_name", drugName);
        wrapper.orderByAsc("id");
        return drugMapper.selectList(wrapper);
    }
}
