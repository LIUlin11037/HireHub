package com.ll.hirehub.company.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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

import java.util.ArrayList;
import java.util.List;

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
                            .properties("industry", p -> p.text(t -> t.analyzer("ik_max_word")))
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
                            b.filter(f -> f.term(t -> t.field("industry").value(industry)));
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

    @Scheduled(cron = "0 10 * * * ?")
    public void reconcile() {
        List<Company> companies = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getVerifyStatus, 1));
        companies.forEach(this::index);
        log.info("company_index 对账完成，重新索引 {} 个已认证企业", companies.size());
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
