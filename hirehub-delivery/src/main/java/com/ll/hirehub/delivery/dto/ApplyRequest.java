package com.ll.hirehub.delivery.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ApplyRequest {

    @NotNull(message = "职位不能为空")
    private Long jobId;

    @NotNull(message = "简历不能为空")
    private Long resumeId;
}
