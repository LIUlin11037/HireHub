package com.ll.hirehub.job.vo;

import lombok.Data;

import java.util.List;

/** 管理端职位维度统计（见 D-07：{@code GET /api/job/admin/statistics}） */
@Data
public class JobStatisticsVO {

    private long total;
    private long draft;
    private long online;
    private long offline;
    private long full;
    private long totalDeliveryCount;
    private long totalViewCount;

    private List<CountItem> topCities;
    private List<CountItem> topCategories;

    @Data
    public static class CountItem {
        private String label;
        private Long count;

        public CountItem(String label, Long count) {
            this.label = label;
            this.count = count;
        }
    }
}
