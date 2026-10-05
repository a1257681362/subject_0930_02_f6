package com.evops.aqua.med.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 药品目录登记请求：携带休药期天数与泼洒剂量（mg/L）。
 */
public class DrugCreateRequest {

    @NotBlank(message = "药品编码不能为空")
    private String drugCode;

    @NotBlank(message = "药品名称不能为空")
    private String drugName;

    @NotNull(message = "泼洒剂量(mg/L)不能为空")
    @DecimalMin(value = "0.001", message = "泼洒剂量必须大于0")
    private BigDecimal dosageMgPerL;

    @NotNull(message = "休药期天数不能为空")
    @Min(value = 0, message = "休药期天数不能为负")
    private Integer withdrawalDays;

    private String manufacturer;

    @NotBlank(message = "登记人不能为空")
    private String operator;

    private String remark;

    public String getDrugCode() { return drugCode; }
    public void setDrugCode(String drugCode) { this.drugCode = drugCode; }
    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public BigDecimal getDosageMgPerL() { return dosageMgPerL; }
    public void setDosageMgPerL(BigDecimal dosageMgPerL) { this.dosageMgPerL = dosageMgPerL; }
    public Integer getWithdrawalDays() { return withdrawalDays; }
    public void setWithdrawalDays(Integer withdrawalDays) { this.withdrawalDays = withdrawalDays; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
