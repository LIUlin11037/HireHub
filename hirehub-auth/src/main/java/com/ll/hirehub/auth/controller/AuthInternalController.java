package com.ll.hirehub.auth.controller;

import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.auth.entity.SysUser;
import com.ll.hirehub.auth.mapper.SysUserMapper;
import com.ll.hirehub.auth.service.OperationLogService;
import com.ll.hirehub.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（服务间 Feign 调用，不走网关）
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class AuthInternalController {

    private final SysUserMapper userMapper;
    private final OperationLogService operationLogService;

    @GetMapping("/real-name-status")
    public Result<Integer> getRealNameStatus(@RequestParam Long userId) {
        SysUser user = userMapper.selectById(userId);
        return Result.ok(user == null ? null : user.getRealNameStatus());
    }

    /**
     * 审计上报入口（company / job 调用，见 D-07）。
     * 内部接口由网关隔离（只路由 /api/**），所以这里不再重复鉴权。
     */
    @PostMapping("/operation-log")
    public Result<Void> saveOperationLog(@RequestBody OperationLogRequest req) {
        operationLogService.save(req);
        return Result.ok();
    }
}
