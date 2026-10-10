package com.ll.hirehub.delivery.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投递的对外视图（见 D-41）。
 * <p>
 * <b>为什么要这个 VO</b>：{@code Delivery} 实体只有 8 个字段（全是从库里直接查出来的关联 ID），
 * 前端渲染"我的投递 / 收到的投递"时只能显示「投递 #501 · 职位ID 1」，
 * 想补个职位标题就得再发 N 次 {@code GET /api/job/{id}} —— 一页 10 条就是 11 次请求。
 * <p>
 * 这里冗余三个<b>展示名</b>，由服务端一次性组装（一页固定 3 次批量远程调用，与条数无关）：
 * <ul>
 *   <li>{@code jobTitle} —— 来自 job 服务；</li>
 *   <li>{@code companyName} —— 来自 company 服务；</li>
 *   <li>{@code seekerName} —— 来自 <b>简历</b>（求职者自己填的展示名），
 *       不是 auth 的真实姓名 —— 后者属于 D-23 隐私模型里"未同意不暴露"的字段。</li>
 * </ul>
 * 这三个字段在 Feign 降级时会为空，此时列表照常渲染，只是少了名字。
 */
@Data
public class DeliveryVO {

    private Long id;

    private Long jobId;
    private String jobTitle;

    private Long resumeId;

    private Long seekerId;
    private String seekerName;

    private Long companyId;
    private String companyName;

    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
