package com.ll.hirehub.resume.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口（供 delivery 服务取简历做快照，见 D-26）
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class ResumeInternalController {

    private final ResumeMapper resumeMapper;

    @GetMapping("/{id}")
    public Result<ResumeDTO> get(@PathVariable Long id) {
        Resume r = resumeMapper.selectById(id);
        if (r == null) {
            return Result.ok(null);
        }
        ResumeDTO dto = new ResumeDTO();
        dto.setId(r.getId());
        dto.setUserId(r.getUserId());
        dto.setTitle(r.getTitle());
        dto.setName(r.getName());
        dto.setGender(r.getGender());
        dto.setBirth(r.getBirth());
        dto.setPhone(r.getPhone());
        dto.setEmail(r.getEmail());
        dto.setExpectCity(r.getExpectCity());
        dto.setExpectSalaryMin(r.getExpectSalaryMin());
        dto.setExpectSalaryMax(r.getExpectSalaryMax());
        dto.setExpectPosition(r.getExpectPosition());
        dto.setStatus(r.getStatus());
        return Result.ok(dto);
    }

    /**
     * 批量查简历上的姓名（投递列表冗余 seekerName 用，见 D-41）。
     * <p>
     * 取的是<b>简历里求职者自己填写的展示名</b>，不是 auth 的真实姓名 ——
     * 真实姓名属于 D-23 隐私模型里"未同意不暴露"的字段，而简历名本来就是填给雇主看的。
     */
    @GetMapping("/names")
    public Result<List<IdNameDTO>> listNames(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.ok(List.of());
        }
        List<Resume> resumes = resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                .select(Resume::getId, Resume::getName)
                .in(Resume::getId, ids));
        return Result.ok(resumes.stream()
                .map(r -> new IdNameDTO(r.getId(), r.getName()))
                .toList());
    }
}
