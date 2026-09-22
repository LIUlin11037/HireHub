package com.ll.hirehub.job.search;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 职位变更事件（在业务事务内发布，事务提交后才真正发 MQ，见 §6.3）。
 */
@Getter
@AllArgsConstructor
public class JobSyncEvent {

    private final Long jobId;
    /** true = 删除（逻辑删除），需要从 ES 移除；false = 新增/变更，需要 upsert */
    private final boolean delete;
}
