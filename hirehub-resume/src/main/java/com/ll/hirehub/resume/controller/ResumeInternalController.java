package com.ll.hirehub.resume.controller;

import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
