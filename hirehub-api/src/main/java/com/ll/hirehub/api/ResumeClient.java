package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * resume 服务内部契约
 */
@FeignClient(name = "hirehub-resume", fallbackFactory = FeignFallbacks.ResumeClientFallbackFactory.class)
public interface ResumeClient {

    @GetMapping("/internal/{id}")
    Result<ResumeDTO> getResume(@PathVariable("id") Long id);

    /**
     * 批量查简历上的姓名（投递列表冗余 seekerName 用，见 D-41）。
     * <p>
     * <b>为什么取简历的 name 而不是 auth 的真实姓名</b>：简历里的 name 是求职者
     * <b>自己填写、用于投递给雇主看</b>的展示名；真实姓名属于 D-23 隐私模型里
     * "未同意不暴露"的字段。用简历名既不破坏隐私模型，又正是 HR 想看到的东西。
     */
    @GetMapping("/internal/names")
    Result<List<IdNameDTO>> listNames(@RequestParam("ids") List<Long> ids);
}
