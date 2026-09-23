package com.ll.hirehub.resume.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 前端直传 MinIO 成功后回调确认（见 §6.6）。
 */
@Data
public class ConfirmUploadRequest {

    @NotBlank(message = "objectKey 不能为空")
    private String objectKey;

    private String fileName;
}
