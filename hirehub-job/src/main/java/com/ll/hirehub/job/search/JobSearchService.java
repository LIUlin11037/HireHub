package com.ll.hirehub.job.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 职位 ES 读写（job_index，见架构文档 §6.3 / D-22）。
 * <p>
 * 关键点：
 * <ul>
 *   <li><b>分词</b>：标题/技能/描述建索引用 {@code ik_max_word}（细粒度），查询用 {@code ik_smart}（粗粒度）。</li>
 *   <li><b>相关性</b>：{@code multi_match} + 字段权重（标题 3 &gt; 技能 2 &gt; 描述 1）。</li>
 *   <li><b>过滤</b>：城市 / 学历 / 经验 / 薪资区间走 {@code filter}（不参与打分，可缓存）。</li>
 *   <li><b>高亮</b>：标题 + 技能关键词。</li>
 *   <li><b>深分页</b>：{@code search_after}（score + id 双排序游标），不用 {@code from+size}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSearchService {

    public static final String INDEX = "job_index";

    private final ElasticsearchClient client;
    private final JobMapper jobMapper;
    private final ObjectMapper objectMapper;

    /** 幂等建索引（含映射，analyzer 由 IK 插件注册，无需在 settings 里再定义） */
    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
            if (exists) {
                return;
            }
            // 单节点集群必须 replicas=0，否则索引状态恒为 yellow（unassigned_shards=1），见踩坑记录
            client.indices().create(c -> c.index(INDEX)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m
                    .properties("id", p -> p.long_(x -> x))
                    .properties("companyId", p -> p.long_(x -> x))
                    .properties("categoryId", p -> p.long_(x -> x))
                    .properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                    .properties("skills", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                    .properties("description", p -> p.text(t -> t.analyzer("ik_max_word")))
                    .properties("city", p -> p.keyword(k -> k))
                    .properties("district", p -> p.keyword(k -> k))
                    .properties("education", p -> p.keyword(k -> k))
                    .properties("experience", p -> p.keyword(k -> k))
                    .properties("salaryMin", p -> p.integer(x -> x))
                    .properties("salaryMax", p -> p.integer(x -> x))
                    .properties("status", p -> p.integer(x -> x))
                    .properties("publishTime", p -> p.long_(x -> x))
            ));
            log.info("创建 job_index 完成");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 ES 索引失败（确认 ES 已启动且已安装 IK 插件）", e);
        }
    }

    public void index(Job job) {
        try {
            client.index(i -> i.index(INDEX).id(String.valueOf(job.getId())).document(toDoc(job)));
        } catch (Exception e) {
            log.warn("索引职位 {} 失败（对账任务会补偿）: {}", job.getId(), e.getMessage());
        }
    }

    public void delete(Long jobId) {
        try {
            client.delete(d -> d.index(INDEX).id(String.valueOf(jobId)));
        } catch (Exception e) {
            log.warn("删除职位 {} 索引失败（对账任务会补偿）: {}", jobId, e.getMessage());
        }
    }

    /** 搜索：关键词 + 过滤 + 高亮 + search_after 深分页 */
    public SearchResult search(String keyword, String city, Integer salaryMin, Integer salaryMax,
                               String education, String experience, String searchAfter, int size) {
        var req = new co.elastic.clients.elasticsearch.core.SearchRequest.Builder()
                .index(INDEX)
                .query(q -> q.bool(b -> {
                    b.must(m -> m.multiMatch(mm -> mm.query(keyword)
                            .fields("title^3", "skills^2", "description")));
                    if (city != null && !city.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("city").value(city)));
                    }
                    if (education != null && !education.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("education").value(education)));
                    }
                    if (experience != null && !experience.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("experience").value(experience)));
                    }
                    // 薪资区间重叠：职位薪资区间与用户期望区间有交集
                    if (salaryMax != null) {
                        b.filter(f -> f.range(r -> r.field("salaryMin").lte(JsonData.of(salaryMax))));
                    }
                    if (salaryMin != null) {
                        b.filter(f -> f.range(r -> r.field("salaryMax").gte(JsonData.of(salaryMin))));
                    }
                    b.filter(f -> f.term(t -> t.field("status").value(1)));   // 只搜招聘中
                    return b;
                }))
                .highlight(h -> h
                        .fields("title", hf -> hf)
                        .fields("skills", hf -> hf))
                .sort(s -> s.score(sc -> sc.order(SortOrder.Desc)))           // 相关性优先
                .sort(s -> s.field(f -> f.field("id").order(SortOrder.Desc))) // id 作 tiebreaker，供 search_after
                .size(Math.min(size, 50))
                .trackTotalHits(th -> th.enabled(true));

        List<FieldValue> after = parseSearchAfter(searchAfter);
        if (after != null) {
            req.searchAfter(after);
        }

        SearchResult result = new SearchResult();
        try {
            SearchResponse<JobDocument> resp = client.search(req.build(), JobDocument.class);
            result.setTotal(resp.hits().total() == null ? 0 : resp.hits().total().value());
            List<SearchHit> hits = new ArrayList<>();
            Hit<JobDocument> last = null;
            for (Hit<JobDocument> hit : resp.hits().hits()) {
                JobDocument d = hit.source();
                SearchHit sh = new SearchHit();
                sh.setId(d.getId());
                sh.setTitle(d.getTitle());
                sh.setCity(d.getCity());
                sh.setSalaryMin(d.getSalaryMin());
                sh.setSalaryMax(d.getSalaryMax());
                sh.setEducation(d.getEducation());
                sh.setExperience(d.getExperience());
                sh.setSkills(d.getSkills());
                sh.setHighlightTitle(joinHighlight(hit, "title"));
                sh.setHighlightSkills(joinHighlight(hit, "skills"));
                hits.add(sh);
                last = hit;
            }
            result.setJobs(hits);
            if (last != null && last.source() != null) {
                result.setSearchAfter("[" + last.score() + "," + last.source().getId() + "]");
            }
        } catch (Exception e) {
            log.error("ES 搜索失败: {}", e.getMessage(), e);
            result.setTotal(0);
            result.setJobs(new ArrayList<>());
        }
        return result;
    }

    /** 每小时对账：把 DB 里所有招聘中的职位重新索引（演示用全量；生产按 (id, update_time) 增量比对） */
    @Scheduled(cron = "0 5 * * * ?")
    public void reconcile() {
        List<Job> jobs = jobMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Job>()
                .eq(Job::getStatus, 1));
        for (Job job : jobs) {
            index(job);
        }
        log.info("job_index 对账完成，重新索引 {} 个招聘中职位", jobs.size());
    }

    private JobDocument toDoc(Job job) {
        JobDocument d = new JobDocument();
        d.setId(job.getId());
        d.setCompanyId(job.getCompanyId());
        d.setTitle(job.getTitle());
        d.setSkills(job.getSkills());
        d.setCity(job.getCity());
        d.setDistrict(job.getDistrict());
        d.setSalaryMin(job.getSalaryMin());
        d.setSalaryMax(job.getSalaryMax());
        d.setEducation(job.getEducation());
        d.setExperience(job.getExperience());
        d.setCategoryId(job.getCategoryId());
        d.setDescription(job.getDescription());
        d.setStatus(job.getStatus());
        long publish = job.getPublishTime() != null
                ? job.getPublishTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : (job.getCreateTime() != null
                    ? job.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 0L);
        d.setPublishTime(publish);
        return d;
    }

    private List<FieldValue> parseSearchAfter(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            JsonNode n = objectMapper.readTree(s);
            List<FieldValue> v = new ArrayList<>();
            v.add(FieldValue.of(n.get(0).asDouble()));
            v.add(FieldValue.of(n.get(1).asLong()));
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private String joinHighlight(Hit<JobDocument> hit, String field) {
        if (hit.highlight() == null || hit.highlight().get(field) == null) {
            return null;
        }
        return String.join(" … ", hit.highlight().get(field));
    }

    @Data
    public static class SearchResult {
        private long total;
        private List<SearchHit> jobs;
        private String searchAfter;
    }

    @Data
    public static class SearchHit {
        private Long id;
        private String title;
        private String city;
        private Integer salaryMin;
        private Integer salaryMax;
        private String education;
        private String experience;
        private String skills;
        private String highlightTitle;
        private String highlightSkills;
    }
}
