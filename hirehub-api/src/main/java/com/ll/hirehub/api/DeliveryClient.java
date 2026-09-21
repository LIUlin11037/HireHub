package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * delivery 服务内部契约
 */
@FeignClient(name = "hirehub-delivery", fallbackFactory = FeignFallbacks.DeliveryClientFallbackFactory.class)
public interface DeliveryClient {

    @GetMapping("/internal/{id}")
    Result<DeliveryDTO> getDelivery(@PathVariable("id") Long id);
}
