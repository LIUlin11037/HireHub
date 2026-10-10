package com.ll.hirehub.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用的「ID → 展示名」批量查询结果（见 D-41）。
 * <p>
 * 存在的理由：投递列表要在每条记录上显示 jobTitle / companyName / seekerName。
 * 如果逐条 Feign 去查，一页 10 条就是 30 次调用（N+1）。
 * 改成 <b>按 ID 集合批量查</b>后，一页只发 <b>3 次</b>调用（职位 / 企业 / 简历各一次）。
 * <p>
 * 用 {@code List<IdNameDTO>} 而不是 {@code Map<Long,String>}：Map 的 key 只能是字符串，
 * 序列化后变成 {@code {"1":"Java 工程师"}}，OpenAPI 里也没法表达类型，
 * 不利于前端生成类型定义。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdNameDTO {

    private Long id;
    private String name;
}
