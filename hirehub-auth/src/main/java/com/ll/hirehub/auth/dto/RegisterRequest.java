package com.ll.hirehub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码强度：8~32 位，且必须同时包含字母和数字。
     * <p>
     * 为什么在这里拦：注册是唯一写入密码的入口，校验放在 DTO 上就覆盖了所有调用方；
     * 放在 Service 里容易将来被某个"内部开通账号"的路径绕过去。
     * <p>
     * ⚠️ 现有测试数据里的 {@code 123456} 因此不再合法 —— 验证脚本已统一改成 {@code 123456ab}。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需为 8~32 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    private String password;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    private String nickname;
}
