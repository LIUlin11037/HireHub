package com.ll.hirehub.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.entity.ResumeParseDetail;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import com.ll.hirehub.resume.mapper.ResumeParseDetailMapper;
import com.ll.hirehub.resume.search.ResumeIndexService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历解析：附件 → 结构化字段 → resume_index（见 Q-03）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>异步</b>：由 {@code resume.parse} 消息触发，不阻塞上传接口的响应。解析一份 PDF 要几百毫秒到数秒，
 *       放在上传请求里会让用户盯着转圈，也会拖垮上传接口的吞吐。</li>
 *   <li><b>解析结果与用户数据分表</b>：机器推断是"建议"，只回填<b>用户没填</b>的字段，
 *       绝不覆盖用户手填内容。</li>
 *   <li><b>失败要能说清原因</b>：扫描件没有文本层、加密文件都会落到 {@code parse_status=3} 并带原因，
 *       而不是笼统"解析失败"——排障与重试都依赖这个原因。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseService {

    /** 0 待解析 / 1 解析中 / 2 成功 / 3 失败（与 resume.parse_status 对齐） */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern LABEL_NAME = Pattern.compile("姓\\s*名[:：]\\s*([\\u4e00-\\u9fa5]{2,4})");
    private static final Pattern LABEL_SKILLS = Pattern.compile("(?:技能|专长|技术栈)[:：]?\\s*([^\\n]{2,200})");
    private static final Pattern WORK_YEARS = Pattern.compile("(\\d{1,2})\\s*年(?:以上)?(?:工作)?经验");
    private static final Pattern DEGREE = Pattern.compile("(博士|硕士|研究生|本科|学士|大专|专科|高中|中专)");

    /** 技能词典：命中即写入索引，用于人才库关键词检索 */
    private static final List<String> SKILL_DICT = List.of(
            "Java", "Spring Boot", "Spring Cloud", "Spring", "MyBatis", "MySQL", "Redis", "RabbitMQ",
            "Kafka", "Elasticsearch", "Docker", "Kubernetes", "Linux", "Python", "Go", "C++", "JavaScript",
            "TypeScript", "Vue", "React", "Node.js", "Nginx", "MongoDB", "PostgreSQL", "Hadoop", "Spark",
            "Flink", "微服务", "分布式", "高并发", "JVM", "多线程", "Netty", "Dubbo", "Sentinel", "MinIO");

    private final MinioClient minioClient;
    private final ResumeMapper resumeMapper;
    private final ResumeParseDetailMapper detailMapper;
    private final ResumeIndexService indexService;
    private final ObjectMapper objectMapper;

    @Value("${hirehub.minio.bucket:resume}")
    private String bucket;

    /**
     * 解析一份简历附件。幂等：重复消息会重新解析并覆盖解析结果（用户可主动重试）。
     * 全程不抛异常——失败写进 parse_status=3，让调用方（MQ 消费者）能正常 ACK，
     * 否则消息会无限重入队，把队列堵死。
     */
    public void parse(Long resumeId, String objectKey) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            log.warn("简历 {} 已不存在，跳过解析", resumeId);
            return;
        }
        markStatus(resume, STATUS_RUNNING, null);

        try {
            byte[] bytes = download(objectKey);
            String sourceType = detectType(objectKey);
            String text = extractText(sourceType, bytes);
            if (text == null || text.isBlank()) {
                fail(resume, "未能从文件中提取到文本（可能是扫描件 / 图片型 PDF，无文本层）");
                return;
            }

            ResumeParseDetail detail = analyze(resume, sourceType, text);
            upsertDetail(detail);
            backfillEmptyFields(resume, detail);

            markStatus(resume, STATUS_SUCCESS, null);
            // 解析路径跑在消费者的 @Transactional 里（见 ResumeParseHandler），
            // 索引必须等提交后再写，否则事务回滚会留下"库里没解析成功、索引里已有技能"的漂移
            indexService.refreshAfterCommit(resumeId);
            log.info("简历 {} 解析完成: type={} skills={} workYears={}",
                    resumeId, sourceType, detail.getSkills(), detail.getWorkYears());
        } catch (InvalidPasswordException e) {
            fail(resume, "文件已加密，无法解析（请上传未加密的简历）");
        } catch (Exception e) {
            log.error("简历 {} 解析失败: {}", resumeId, e.getMessage(), e);
            fail(resume, "解析失败：" + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private byte[] download(String objectKey) throws Exception {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectKey).build());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private String detectType(String objectKey) {
        String lower = objectKey == null ? "" : objectKey.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".docx")) {
            return "DOCX";
        }
        if (lower.endsWith(".xlsx")) {
            return "XLSX";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return "TXT";
        }
        return "UNKNOWN";
    }

    private String extractText(String sourceType, byte[] bytes) throws Exception {
        return switch (sourceType) {
            case "PDF" -> extractPdf(bytes);
            case "DOCX" -> extractDocx(bytes);
            case "XLSX" -> extractXlsx(bytes);
            case "TXT" -> new String(bytes, StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("不支持的文件类型（仅支持 PDF / DOCX / XLSX / TXT）");
        };
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            document.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
            // 很多简历把技能写成表格，段落遍历拿不到，必须再扫一遍表格
            document.getTables().forEach(table -> table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell -> sb.append(cell.getText()).append('\n'))));
        }
        return sb.toString();
    }

    private String extractXlsx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        sb.append(cell.toString()).append('\t');
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 从文本里抽取结构化字段。抽取不到的留空，交由用户手填——不猜。 */
    private ResumeParseDetail analyze(Resume resume, String sourceType, String text) {
        ResumeParseDetail detail = new ResumeParseDetail();
        detail.setResumeId(resume.getId());
        detail.setUserId(resume.getUserId());
        detail.setSourceType(sourceType);
        detail.setRawText(text.length() > 20000 ? text.substring(0, 20000) : text);
        detail.setSkills(toJson(extractSkills(text)));
        detail.setEducation(extractDegree(text));
        detail.setWorkYears(extractWorkYears(text));
        detail.setWorkExperience(extractWorkExperience(text));
        detail.setCreateTime(LocalDateTime.now());
        detail.setUpdateTime(LocalDateTime.now());
        return detail;
    }

    private List<String> extractSkills(String text) {
        Set<String> hit = new LinkedHashSet<>();
        String upper = text.toUpperCase(Locale.ROOT);
        for (String skill : SKILL_DICT) {
            if (upper.contains(skill.toUpperCase(Locale.ROOT))) {
                hit.add(skill);
            }
        }
        Matcher matcher = LABEL_SKILLS.matcher(text);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[,，、;；/|·\\s]+")) {
                String cleaned = part.trim();
                if (cleaned.length() >= 2 && cleaned.length() <= 20) {
                    hit.add(cleaned);
                }
            }
        }
        return new ArrayList<>(hit);
    }

    private String extractDegree(String text) {
        Matcher matcher = DEGREE.matcher(text);
        // 取最高学历：文本里通常同时出现"本科""硕士"，按学历高低取最高的那个
        String best = null;
        int bestRank = -1;
        while (matcher.find()) {
            String degree = matcher.group(1);
            int rank = degreeRank(degree);
            if (rank > bestRank) {
                bestRank = rank;
                best = normalizeDegree(degree);
            }
        }
        return best;
    }

    private int degreeRank(String degree) {
        return switch (degree) {
            case "博士" -> 5;
            case "硕士", "研究生" -> 4;
            case "本科", "学士" -> 3;
            case "大专", "专科" -> 2;
            default -> 1;
        };
    }

    private String normalizeDegree(String degree) {
        return switch (degree) {
            case "研究生" -> "硕士";
            case "学士" -> "本科";
            case "专科" -> "大专";
            default -> degree;
        };
    }

    private Integer extractWorkYears(String text) {
        Matcher matcher = WORK_YEARS.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractWorkExperience(String text) {
        int idx = text.indexOf("工作经历");
        if (idx < 0) {
            idx = text.indexOf("工作经验");
        }
        if (idx < 0) {
            return null;
        }
        String tail = text.substring(idx, Math.min(text.length(), idx + 1200));
        return tail.replaceAll("\\s{2,}", " ").trim();
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void upsertDetail(ResumeParseDetail detail) {
        ResumeParseDetail existing = detailMapper.selectOne(new LambdaQueryWrapper<ResumeParseDetail>()
                .eq(ResumeParseDetail::getResumeId, detail.getResumeId()));
        if (existing == null) {
            detailMapper.insert(detail);
        } else {
            detail.setId(existing.getId());
            detail.setCreateTime(existing.getCreateTime());
            detailMapper.updateById(detail);
        }
    }

    /** 只回填用户没填的字段：机器解析不能覆盖用户手填内容 */
    private void backfillEmptyFields(Resume resume, ResumeParseDetail detail) {
        boolean changed = false;
        if (isBlank(resume.getPhone())) {
            String phone = firstMatch(PHONE, detail.getRawText());
            if (phone != null) {
                resume.setPhone(phone);
                changed = true;
            }
        }
        if (isBlank(resume.getEmail())) {
            String email = firstMatch(EMAIL, detail.getRawText());
            if (email != null) {
                resume.setEmail(email);
                changed = true;
            }
        }
        if (isBlank(resume.getName())) {
            Matcher matcher = LABEL_NAME.matcher(detail.getRawText());
            if (matcher.find()) {
                resume.setName(matcher.group(1));
                changed = true;
            }
        }
        if (changed) {
            resumeMapper.updateById(resume);
        }
    }

    private String firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void markStatus(Resume resume, int status, String error) {
        resume.setParseStatus(status);
        resumeMapper.updateById(resume);
        if (error != null) {
            log.warn("简历 {} 解析状态={} 原因={}", resume.getId(), status, error);
        }
    }

    private void fail(Resume resume, String reason) {
        resume.setParseStatus(STATUS_FAILED);
        resumeMapper.updateById(resume);

        ResumeParseDetail detail = detailMapper.selectOne(
                new LambdaQueryWrapper<ResumeParseDetail>().eq(ResumeParseDetail::getResumeId, resume.getId()));
        if (detail == null) {
            detail = new ResumeParseDetail();
            detail.setResumeId(resume.getId());
            detail.setUserId(resume.getUserId());
            detail.setSourceType("UNKNOWN");
            detail.setCreateTime(LocalDateTime.now());
        }
        detail.setParseError(reason);
        detail.setUpdateTime(LocalDateTime.now());
        upsertDetail(detail);
        log.warn("简历 {} 解析失败: {}", resume.getId(), reason);
    }
}
