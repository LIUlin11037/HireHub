package com.ll.hirehub.company.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.mapper.CompanyMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 企业 ES 读写（company_index，见 §6.3 / D-22）。
 * <p>
 * 只索引「已认证」企业（verify_status=1），保证平台展示的企业信息全量可信（D-20）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanySearchService {

    public static final String INDEX = "company_index";

    private final ElasticsearchClient client;
    private final CompanyMapper companyMapper;

    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
            if (exists) {
                return;
            }
            client.indices().create(c -> c.index(INDEX)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m
                            .properties("id", p -> p.long_(x -> x))
                            .properties("name", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            // 审计 F-08：industry 需要 keyword 子字段——term 精确过滤打到分词后的 text 字段永远匹配不上
                            .properties("industry", p -> p.text(t -> t.analyzer("ik_max_word")
                                    .fields("keyword", f -> f.keyword(k -> k))))
                            .properties("description", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("scale", p -> p.keyword(k -> k))
                            .properties("city", p -> p.keyword(k -> k))
                            .properties("verifyStatus", p -> p.integer(x -> x))
                    ));
            log.info("创建 company_index 完成");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 company_index 失败", e);
        }
    }

    public void index(Company company) {
        try {
            client.index(i -> i.index(INDEX).id(String.valueOf(company.getId())).document(toDoc(company)));
        } catch (Exception e) {
            log.warn("索引企业 {} 失败（对账任务会补偿）: {}", company.getId(), e.getMessage());
        }
    }

    /** 审计 F-07：反向对账需要删除能力（企业被撤销认证 / 删除时清理索引） */
    public void delete(Long companyId) {
        try {
            client.delete(d -> d.index(INDEX).id(String.valueOf(companyId)));
        } catch (Exception e) {
            log.warn("删除企业 {} 索引失败（对账任务会补偿）: {}", companyId, e.getMessage());
        }
    }

    /** 搜索：关键词（名称^3 / 行业^2 / 描述^1）+ 城市/行业过滤；只展示已认证企业 */
    public SearchResult search(String keyword, String city, String industry, int size) {
        SearchResult result = new SearchResult();
        try {
            SearchResponse<CompanyDocument> resp = client.search(s -> s
                    .index(INDEX)
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm.query(keyword)
                                .fields("name^3", "industry^2", "description")));
                        if (city != null && !city.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("city").value(city)));
                        }
                        if (industry != null && !industry.isBlank()) {
                            // 审计 F-08：精确过滤走 keyword 子字段（text 经 IK 切词后 term 全串匹配不上）
                            b.filter(f -> f.term(t -> t.field("industry.keyword").value(industry)));
                        }
                        b.filter(f -> f.term(t -> t.field("verifyStatus").value(1)));
                        return b;
                    }))
                    .highlight(h -> h.fields("name", hf -> hf).fields("industry", hf -> hf))
                    .size(Math.min(size, 50))
                    .trackTotalHits(th -> th.enabled(true)),
                    CompanyDocument.class);

            result.setTotal(resp.hits().total() == null ? 0 : resp.hits().total().value());
            List<Hit2> hits = new ArrayList<>();
            for (Hit<CompanyDocument> hit : resp.hits().hits()) {
                CompanyDocument d = hit.source();
                Hit2 h = new Hit2();
                h.setId(d.getId());
                h.setName(d.getName());
                h.setIndustry(d.getIndustry());
                h.setScale(d.getScale());
                h.setCity(d.getCity());
                h.setHighlightName(joinHighlight(hit, "name"));
                h.setHighlightIndustry(joinHighlight(hit, "industry"));
                hits.add(h);
            }
            result.setCompanies(hits);
        } catch (Exception e) {
            log.error("企业 ES 搜索失败: {}", e.getMessage(), e);
            result.setTotal(0);
            result.setCompanies(new ArrayList<>());
        }
        return result;
    }

    /** 对账翻页参数（与 resume 侧一致）：_doc 排序全量扫描 */
    private static final int SCAN_PAGE_SIZE = 1000;
    private static final int SCAN_MAX_PAGES = 100;

    /**
     * 每小时对账（审计 F-07 改双向）：
     * ① 正向——DB 中已认证企业全量重灌，纠正字段漂移与漏写；
     * ② 反向——扫出 ES 全量 id，清理 DB 中已不存在或认证已被撤销的陈旧文档。
     *    此前只补不清："撤销认证"的更新消息一旦丢失，陈旧文档永久可搜（对照组 ResumeIndexService 是双向的）。
     */
    @Scheduled(cron = "0 10 * * * ?")
    public void reconcile() {
        try {
            List<Company> companies = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                    .eq(Company::getVerifyStatus, 1));
            companies.forEach(this::index);
            Set<Long> expected = companies.stream().map(Company::getId).collect(Collectors.toSet());
            Set<Long> stale = actualIds();
            stale.removeAll(expected);
            stale.forEach(this::delete);
            log.info("company_index 对账完成，重新索引 {} 个已认证企业，清理 {} 个陈旧文档", companies.size(), stale.size());
        } catch (Exception e) {
            log.warn("company_index 对账失败（下轮重试）: {}", e.getMessage());
        }
    }

    /**
     * 实际集合：索引里现有的全部企业 id。
     * 必须翻页取全（_doc 排序 + search_after），否则超过单页大小后脏文档永远清不掉——同 resume 对账。
     */
    private Set<Long> actualIds() throws IOException {
        Set<Long> ids = new HashSet<>();
        List<FieldValue> cursor = null;
        for (int page = 0; page < SCAN_MAX_PAGES; page++) {
            final List<FieldValue> searchAfter = cursor;
            var response = client.search(s -> {
                s.index(INDEX).size(SCAN_PAGE_SIZE).source(src -> src.fetch(false))
                        .query(Query.of(q -> q.matchAll(m -> m)))
                        .sort(so -> so.field(f -> f.field("_doc").order(SortOrder.Asc)));
                if (searchAfter != null && !searchAfter.isEmpty()) {
                    s.searchAfter(searchAfter);
                }
                return s;
            }, Void.class);

            var hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            hits.forEach(hit -> {
                if (hit.id() != null) {
                    ids.add(Long.valueOf(hit.id()));
                }
            });
            if (hits.size() < SCAN_PAGE_SIZE) {
                break;
            }
            cursor = hits.get(hits.size() - 1).sort();
            if (cursor == null || cursor.isEmpty()) {
                // 拿不到游标就无法继续翻页；宁可少扫也不能死循环
                log.warn("company_index 对账翻页中断：命中缺少 sort 值，本轮只扫描了 {} 条", ids.size());
                break;
            }
            if (page == SCAN_MAX_PAGES - 1) {
                log.warn("company_index 对账达到扫描上限（{} 页），本轮未覆盖全部文档", SCAN_MAX_PAGES);
            }
        }
        return ids;
    }

    private CompanyDocument toDoc(Company c) {
        CompanyDocument d = new CompanyDocument();
        d.setId(c.getId());
        d.setName(c.getName());
        d.setIndustry(c.getIndustry());
        d.setScale(c.getScale());
        d.setCity(c.getCity());
        d.setDescription(c.getDescription());
        d.setVerifyStatus(c.getVerifyStatus());
        return d;
    }

    private String joinHighlight(Hit<CompanyDocument> hit, String field) {
        if (hit.highlight() == null || hit.highlight().get(field) == null) {
            return null;
        }
        return String.join(" … ", hit.highlight().get(field));
    }

    @Data
    public static class SearchResult {
        private long total;
        private List<Hit2> companies;
    }

    @Data
    public static class Hit2 {
        private Long id;
        private String name;
        private String industry;
        private String scale;
        private String city;
        private String highlightName;
        private String highlightIndustry;
    }
}
