package com.ll.hirehub.job.vo;

import com.ll.hirehub.job.entity.Job;
import lombok.Data;

import java.util.List;

/**
 * 分页的职位列表（见 D-41）。
 * <p>
 * 字段名刻意与 {@code /api/job/search} 的返回保持一致（{@code total} + {@code jobs}），
 * 这样前端一套渲染逻辑能吃两个接口 —— 之前"列表字段名不统一"已经踩过一次坑。
 */
@Data
public class JobPageVO {

    private long total;

    private List<Job> jobs;
}
