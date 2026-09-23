package com.ll.hirehub.company.search;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 企业变更事件（事务提交后发 MQ 同步 company_index，见 §6.3）。
 */
@Getter
@AllArgsConstructor
public class CompanySyncEvent {

    private final Long companyId;
}
