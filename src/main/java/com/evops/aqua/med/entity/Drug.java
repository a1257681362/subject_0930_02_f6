package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 药品目录：一种药品一行，携带休药期天数与泼洒剂量。
 * 剂量单位 mg/L（单位水体浓度），用量 = 剂量 × 水体（mg→g 除以 1000）。
 */
@TableName("t_med_drug")
public class Drug extends BaseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private String drugCode;
    private String drugName;
    private BigDecimal dosageMgPerL;
    private Integer withdrawalDays;
    private String manufacturer;
    private String status;
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
