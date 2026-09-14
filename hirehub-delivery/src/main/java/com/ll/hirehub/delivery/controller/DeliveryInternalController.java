package com.ll.hirehub.delivery.controller;

import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.delivery.entity.Delivery;
import com.ll.hirehub.delivery.mapper.DeliveryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（供 interview 服务取投递的 seeker/company）
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class DeliveryInternalController {

    private final DeliveryMapper deliveryMapper;

    @GetMapping("/{id}")
    public Result<DeliveryDTO> get(@PathVariable Long id) {
        Delivery d = deliveryMapper.selectById(id);
        if (d == null) {
            return Result.ok(null);
        }
        DeliveryDTO dto = new DeliveryDTO();
        dto.setId(d.getId());
        dto.setJobId(d.getJobId());
        dto.setResumeId(d.getResumeId());
        dto.setSeekerId(d.getSeekerId());
        dto.setCompanyId(d.getCompanyId());
        dto.setStatus(d.getStatus());
        return Result.ok(dto);
    }
}
