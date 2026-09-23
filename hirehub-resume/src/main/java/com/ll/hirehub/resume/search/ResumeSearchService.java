package com.ll.hirehub.resume.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 人才库检索（见 D-23）。
 * <p>
 * 与职位搜索（{@code JobSearchService}）最大的不同：<b>结果集天生受隐私约束</b>。
 * 三层约束在这里同时生效，缺一层都是漏洞：
 * <ol>
 *   <li><b>求职状态 + 公开开关</b>：由写入侧保证（不满足条件的文档根本不在索引里），这里再过滤一次防止旧数据。</li>
 *   <li><b>屏蔽公司</b>：{@code must_not terms blockedCompanyIds [我的公司]}——在职求职者不被现公司看到。</li>
 *   <li><b>可见范围</b>：调用方（Controller）必须先校验"是已认证企业的在职 HR"，否则 403。</li>
 * </ol>
 * 响应体里只有匿名字段——真实姓名与联系方式不在索引文档中，无法被"忘脱敏"泄露。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeSearchService {

    private final ElasticsearchClient client;

    public SearchResult search(Long companyId, String keyword, String city, String education,
                               Integer salaryMin, Integer minWorkYears,
                               String searchAfter, int size) {
        int pageSize = Math.min(Math.max(size, 1), 50);
        try {
            var response = client.search(s -> {
                s.index(ResumeIndexService.INDEX).size(pageSize);
                s.query(q -> q.bool(b -> {
                    if (keyword == null || keyword.isBlank()) {
                        b.must(m -> m.matchAll(mm -> mm));
                    } else {
                        b.must(m -> m.multiMatch(mm -> mm.query(keyword)
                                .fields("expectPosition^3", "skills^2", "anonymousName")));
                    }
                    // 防旧数据：索引里不该有的文档也拦掉（写入侧已保证，这里是第二道）
                    b.filter(f -> f.term(t -> t.field("status").value(1)));
                    if (city != null && !city.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("expectCity").value(city)));
                    }
                    if (education != null && !education.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("education").value(education)));
                    }
                    if (salaryMin != null) {
                        // 「期望薪资上限 ≥ 我给的预算下限」即可沟通，不是精确匹配
                        b.filter(f -> f.range(r -> r.field("expectSalaryMax").gte(
                                co.elastic.clients.json.JsonData.of(salaryMin))));
                    }
                    if (minWorkYears != null) {
                        b.filter(f -> f.range(r -> r.field("workYears").gte(
                                co.elastic.clients.json.JsonData.of(minWorkYears))));
                    }
                    // ★ 屏蔽公司：在职求职者的核心诉求
                    if (companyId != null) {
                        b.mustNot(mn -> mn.term(t -> t.field("blockedCompanyIds").value(companyId)));
                    }
                    return b;
                }));
                s.highlight(h -> h.fields("skills", hf -> hf)
                        .fields("expectPosition", hf -> hf));
                s.sort(so -> so.score(sc -> sc.order(
                                co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                        .sort(so -> so.field(f -> f.field("id")
                                .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
                if (searchAfter != null && !searchAfter.isBlank()) {
                    String[] parts = searchAfter.split(",", 2);
                    List<co.elastic.clients.elasticsearch._types.FieldValue> cursor = new ArrayList<>();
                    cursor.add(co.elastic.clients.elasticsearch._types.FieldValue.of(
                            Double.parseDouble(parts[0])));
                    cursor.add(co.elastic.clients.elasticsearch._types.FieldValue.of(
                            Long.parseLong(parts[1])));
                    s.searchAfter(cursor);
                }
                return s;
            }, ResumeDocument.class);

            SearchResult result = new SearchResult();
            List<Item> items = new ArrayList<>();
            List<co.elastic.clients.elasticsearch._types.FieldValue> lastSort = null;
            for (var hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;   // 源被 _source:false 之类过滤掉时跳过，但不能因此错位游标
                }
                Item item = new Item();
                item.setResume(hit.source());
                item.setHighlights(hit.highlight());
                item.setScore(hit.score());
                items.add(item);
                lastSort = hit.sort();
            }
            result.setItems(items);
            result.setTotal(response.hits().total() == null ? items.size() : response.hits().total().value());
            // 游标必须取自最后一条 <b>命中</b>的 sort 值（不是 items 下标），否则深分页会漏数据
            if (lastSort != null && lastSort.size() >= 2) {
                result.setNextSearchAfter(lastSort.get(0).doubleValue() + "," + lastSort.get(1).longValue());
            }
            return result;
        } catch (Exception e) {
            // 不静默返回空：搜不到人和"搜索不可用"对 HR 是两件事，静默会让问题被埋在业务里
            log.error("人才库检索失败: {}", e.getMessage(), e);
            throw new IllegalStateException("人才库检索暂不可用，请稍后重试", e);
        }
    }

    @Data
    public static class SearchResult {
        private List<Item> items = new ArrayList<>();
        private long total;
        private String nextSearchAfter;
    }

    @Data
    public static class Item {
        private ResumeDocument resume;
        /** 命中的技能 / 期望职位片段，前端直接展示 */
        private java.util.Map<String, List<String>> highlights;
        private Double score;
    }
}
