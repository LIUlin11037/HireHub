package com.ll.hirehub.resume.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.resume.entity.JobPreference;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.entity.ResumeParseDetail;
import com.ll.hirehub.resume.mapper.JobPreferenceMapper;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import com.ll.hirehub.resume.mapper.ResumeParseDetailMapper;
import com.ll.hirehub.resume.service.ResumePrivacyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * resume_index 维护（见 D-23 / Q-03）。
 * <p>
 * <b>与职位索引的两个关键区别</b>：
 * <ol>
 *   <li><b>隐私变更必须实时生效</b>：职位索引可以忍受"对账几分钟后收敛"，
 *       但"关闭曝光后还能被搜到"是隐私事故，所以关闭曝光走<b>同步删除</b>，对账只兜底。</li>
 *   <li><b>必须在数据库事务提交后写</b>：见 {@link #refreshAfterCommit}——
 *       先写 ES 后回滚会让索引比库更"开放"，对隐私索引来说是最危险的方向。</li>
 * </ol>
 * 写入内容经过 {@link ResumePrivacyService#toDocument}，文档里不存在真实姓名与联系方式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeIndexService implements ApplicationRunner {

    public static final String INDEX = "resume_index";

    /** 对账全量扫描的分页大小与页数上限（100 * 1000 = 10 万份简历，足够本项目体量） */
    private static final int SCAN_PAGE_SIZE = 1000;
    private static final int SCAN_MAX_PAGES = 100;

    private final ElasticsearchClient client;
    private final ResumeMapper resumeMapper;
    private final JobPreferenceMapper preferenceMapper;
    private final ResumeParseDetailMapper parseDetailMapper;
    private final ResumePrivacyService privacy;

    @Override
    public void run(ApplicationArguments args) {
        // 建索引失败不能让服务起不来：ES 未就绪时先启动，对账任务会补建（见 reconcile）
        try {
            ensureIndex();
        } catch (Exception e) {
            log.error("启动时创建 resume_index 失败（对账任务会重试）: {}", e.getMessage());
        }
    }

    /** 幂等建索引。单节点必须 replicas=0，否则索引恒为 yellow（见踩坑记录 #12）。 */
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
                            .properties("userId", p -> p.long_(x -> x))
                            .properties("status", p -> p.integer(x -> x))
                            .properties("jobStatus", p -> p.keyword(k -> k))
                            .properties("anonymousName", p -> p.text(t -> t
                                    .analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("expectCity", p -> p.keyword(k -> k))
                            .properties("expectSalaryMin", p -> p.integer(x -> x))
                            .properties("expectSalaryMax", p -> p.integer(x -> x))
                            .properties("expectPosition", p -> p.text(t -> t
                                    .analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("skills", p -> p.text(t -> t
                                    .analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("education", p -> p.keyword(k -> k))
                            .properties("workYears", p -> p.integer(x -> x))
                            .properties("blockedCompanyIds", p -> p.long_(x -> x))
                            .properties("updateTime", p -> p.long_(x -> x))
                    ));
            log.info("创建 resume_index 完成");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 resume_index 失败（确认 ES 已启动且已安装 IK 插件）", e);
        }
    }

    // ------------------------------------------------------------ 事务后写

    /**
     * 事务提交后再刷新索引。<b>调用方在 {@code @Transactional} 方法里必须用这个</b>。
     * <p>
     * 为什么不能直接 {@link #refresh}：写 ES 是外部副作用，不参与数据库回滚。
     * "先改库 → 写 ES → 事务回滚"之后，索引保留了未提交的状态；对隐私字段来说
     * 最坏情况是"库里保密、索引已公开"，等于求职者关了曝光却仍被搜到。
     * 放到 afterCommit 之后，索引只会滞后（可被对账补），不会超前。
     */
    public void refreshAfterCommit(Long resumeId) {
        afterCommit(() -> refresh(resumeId));
    }

    /** 同上，针对"求职状态 / 屏蔽公司"这类影响该用户全部简历的变更 */
    public void refreshByUserAfterCommit(Long userId) {
        afterCommit(() -> refreshByUser(userId));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // 没有事务（如手动重建索引接口）就直接执行
            action.run();
        }
    }

    // ------------------------------------------------------------ 主写路径

    /**
     * 按当前隐私状态重新评估该简历是否应出现在索引里。
     * <p>
     * 这是<b>唯一</b>的写入口：无论触发源是"上传解析完成""改了求职状态"还是"改了屏蔽公司"，
     * 都重新跑一遍完整规则——避免出现"某条路径忘了判断"的漏网。
     */
    public void refresh(Long resumeId) {
        try {
            Resume resume = resumeMapper.selectById(resumeId);
            if (resume == null) {
                remove(resumeId);
                return;
            }
            JobPreference preference = preferenceOf(resume.getUserId());
            if (!privacy.searchable(resume, preference)) {
                remove(resumeId);   // 关闭曝光 → 立即从索引删除
                return;
            }
            ResumeDocument doc = privacy.toDocument(resume, preference, parseDetailOf(resumeId));
            client.index(i -> i.index(INDEX).id(String.valueOf(resumeId)).document(doc));
        } catch (Exception e) {
            log.warn("刷新简历索引 {} 失败（对账任务会补偿）: {}", resumeId, e.getMessage());
        }
    }

    /** 求职状态 / 屏蔽公司变化会影响该用户全部简历的可搜性 */
    public void refreshByUser(Long userId) {
        List<Resume> resumes = resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>().eq(Resume::getUserId, userId));
        JobPreference preference = preferenceOf(userId);
        for (Resume resume : resumes) {
            try {
                if (!privacy.searchable(resume, preference)) {
                    remove(resume.getId());
                    continue;
                }
                ResumeDocument doc = privacy.toDocument(resume, preference, parseDetailOf(resume.getId()));
                client.index(i -> i.index(INDEX).id(String.valueOf(resume.getId())).document(doc));
            } catch (Exception e) {
                log.warn("刷新简历索引 {} 失败: {}", resume.getId(), e.getMessage());
            }
        }
    }

    public void remove(Long resumeId) {
        try {
            client.delete(d -> d.index(INDEX).id(String.valueOf(resumeId)));
        } catch (Exception e) {
            log.warn("删除简历索引 {} 失败: {}", resumeId, e.getMessage());
        }
    }

    // -------------------------------------------------------------- 对账

    /**
     * 对账兜底：纠正"该在索引里却不在 / 不该在却在"的漂移。
     * 主路径是同步写，这里只处理诸如 ES 瞬时不可用 / 建索引失败导致的漏写。
     */
    @Scheduled(fixedDelayString = "${hirehub.es.reconcile-interval-ms:600000}", initialDelay = 120_000)
    public void reconcile() {
        try {
            ensureIndex();   // 启动时 ES 可能没起来，这里补建
            Set<Long> expected = expectedIds();
            Set<Long> actual = actualIds();
            Set<Long> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<Long> stale = new HashSet<>(actual);
            stale.removeAll(expected);
            missing.forEach(this::refresh);
            stale.forEach(this::remove);
            if (!missing.isEmpty() || !stale.isEmpty()) {
                log.info("resume_index 对账完成: 补写={} 清理={}", missing.size(), stale.size());
            }
        } catch (Exception e) {
            log.warn("resume_index 对账失败（下轮重试）: {}", e.getMessage());
        }
    }

    /**
     * 期望集合：库中"按隐私规则应当可被搜到"的简历。
     * <p>
     * 求职意向一次性批量捞出来做映射，避免逐条查 job_preference 造成 N+1。
     */
    private Set<Long> expectedIds() {
        List<Resume> resumes = resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getStatus, ResumePrivacyService.RESUME_PUBLIC));
        if (resumes.isEmpty()) {
            return Set.of();
        }
        Set<Long> userIds = resumes.stream()
                .map(Resume::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, JobPreference> preferenceByUser = preferenceMapper
                .selectList(new LambdaQueryWrapper<JobPreference>().in(JobPreference::getUserId, userIds))
                .stream()
                .collect(Collectors.toMap(JobPreference::getUserId, Function.identity(), (a, b) -> a));

        Set<Long> ids = new HashSet<>();
        for (Resume resume : resumes) {
            if (privacy.searchable(resume, preferenceByUser.get(resume.getUserId()))) {
                ids.add(resume.getId());
            }
        }
        return ids;
    }

    /**
     * 实际集合：索引里现有的全部简历 ID。
     * <p>
     * 必须<b>翻页取全</b>——早先写成 {@code size(1000)} 单页是错的：超过 1000 份简历后，
     * 剩下的会被算成"缺失"而每轮全量重灌，同时多出来的脏文档永远清不掉。
     * 用 {@code _doc} 排序翻页：全量扫描场景下它比按字段排序便宜。
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
                log.warn("resume_index 对账翻页中断：命中缺少 sort 值，本轮只扫描了 {} 条", ids.size());
                break;
            }
            if (page == SCAN_MAX_PAGES - 1) {
                log.warn("resume_index 对账达到扫描上限（{} 页），本轮未覆盖全部文档", SCAN_MAX_PAGES);
            }
        }
        return ids;
    }

    private JobPreference preferenceOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return preferenceMapper.selectOne(
                new LambdaQueryWrapper<JobPreference>().eq(JobPreference::getUserId, userId));
    }

    private ResumeParseDetail parseDetailOf(Long resumeId) {
        return parseDetailMapper.selectOne(
                new LambdaQueryWrapper<ResumeParseDetail>().eq(ResumeParseDetail::getResumeId, resumeId));
    }
}
