package com.ll.hirehub.company.search;

import lombok.Data;

/**
 * 企业 ES 文档（company_index，见架构文档 §6.3 / D-22）。
 */
@Data
public class CompanyDocument {

    private Long id;
    private String name;
    private String industry;
    private String scale;
    private String city;
    private String description;
    private Integer verifyStatus;   // 1 = 已认证（只索引/展示已认证企业）
}
