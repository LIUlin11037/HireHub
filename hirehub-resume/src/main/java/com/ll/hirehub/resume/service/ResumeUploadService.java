package com.ll.hirehub.resume.service;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 简历附件上传（预签名直传，见架构文档 §6.6 / D-08）。
 * <p>
 * 流程：
 * <pre>
 *   前端 POST /api/resume/{id}/upload-url   → 拿到 { uploadUrl, objectKey }
 *   前端把文件 PUT 到 uploadUrl（直传 MinIO，不经过本服务）
 *   前端 POST /api/resume/{id}/confirm-upload → 把 objectKey 存到 resume.attachment_key
 * </pre>
 * 解析（PDFBox/POI → 结构化 + ES 索引）属三期（Q-03），本阶段只做上传与元数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService implements ApplicationRunner {

    private final MinioClient minioClient;
    private final ResumeMapper resumeMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${hirehub.minio.bucket:resume}")
    private String bucket;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.warn("初始化 MinIO bucket 失败（确认 MinIO 容器 9010 已启动）: {}", e.getMessage());
        }
    }

    /** 生成预签名 PUT URL（仅本人简历） */
    public Map<String, String> presignPutUrl(Long userId, Long resumeId) {
        requireOwn(userId, resumeId);
        String objectKey = "resume/" + resumeId + "/" + UUID.randomUUID().toString().replace("-", "") + ".pdf";
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(30, TimeUnit.MINUTES)
                    .build());
            Map<String, String> result = new HashMap<>();
            result.put("uploadUrl", url);
            result.put("objectKey", objectKey);
            return result;
        } catch (Exception e) {
            log.error("生成预签名 URL 失败: {}", e.getMessage(), e);
            throw new BusinessException("生成上传链接失败");
        }
    }

    /** 前端直传成功后的回调：把 objectKey 落库，并发消息触发异步解析（见 Q-03） */
    public void confirm(Long userId, Long resumeId, String objectKey) {
        Resume resume = requireOwn(userId, resumeId);
        resume.setAttachmentKey(objectKey);
        resume.setParseStatus(0);   // 待解析
        resumeMapper.updateById(resume);
        publishParseMessage(resumeId, userId, objectKey);
    }

    /**
     * 发解析消息。
     * <p>
     * 这里用直发而不是二期那套本地消息表：解析是<b>幂等且用户可主动重试</b>的能力
     * （{@code POST /api/resume/{id}/reparse}），丢一条消息的后果只是"简历暂时搜不到"，
     * 不涉及资金/状态不可逆。为它再建一张本地消息表，复杂度换不来对应的可靠性收益。
     * 发送失败只记日志——上传本身已经成功，不能因为解析调度失败让前端以为上传失败。
     */
    private void publishParseMessage(Long resumeId, Long userId, String objectKey) {
        try {
            MqPayload.ResumeParse payload = new MqPayload.ResumeParse();
            payload.setResumeId(resumeId);
            payload.setUserId(userId);
            payload.setObjectKey(objectKey);

            MqMessage message = new MqMessage();
            message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
            message.setBizType(MqConst.BizType.RESUME_PARSE);
            message.setBizId(resumeId);
            message.setPayload(objectMapper.writeValueAsString(payload));

            rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_RESUME_PARSE, message);
            log.info("已投递简历解析消息: resumeId={}", resumeId);
        } catch (Exception e) {
            log.error("投递简历解析消息失败（可用 /reparse 手动重试）: resumeId={} err={}",
                    resumeId, e.getMessage());
        }
    }

    private Resume requireOwn(Long userId, Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (!resume.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return resume;
    }
}
