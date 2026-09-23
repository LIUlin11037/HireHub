package com.ll.hirehub.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.hirehub.job.entity.Job;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface JobMapper extends BaseMapper<Job> {

    /**
     * 管理端统计（见 D-07）用的聚合查询。
     * <p>
     * 聚合放到 SQL 里做：把全表拉到内存里 count 在数据量上来后是灾难，
     * 而且 status/city 上都有索引，交给 MySQL 更划算。
     * <p>
     * 注意 join 的是 <b>本库</b>的 job_category —— D-13 禁止跨库 JOIN，
     * 所以职位统计只能用到 job 库自己的表（要企业名得走 Feign）。
     */
    @Select("SELECT status AS label, COUNT(*) AS cnt FROM job WHERE deleted = 0 GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(delivery_count), 0) AS deliveries, "
            + "COALESCE(SUM(view_count), 0) AS views FROM job WHERE deleted = 0")
    Map<String, Object> totals();

    @Select("SELECT city AS label, COUNT(*) AS cnt FROM job "
            + "WHERE deleted = 0 AND city IS NOT NULL AND city <> '' "
            + "GROUP BY city ORDER BY cnt DESC LIMIT 5")
    List<Map<String, Object>> topCities();

    @Select("SELECT c.name AS label, COUNT(*) AS cnt FROM job j "
            + "LEFT JOIN job_category c ON c.id = j.category_id AND c.deleted = 0 "
            + "WHERE j.deleted = 0 GROUP BY c.name ORDER BY cnt DESC LIMIT 5")
    List<Map<String, Object>> topCategories();
}
