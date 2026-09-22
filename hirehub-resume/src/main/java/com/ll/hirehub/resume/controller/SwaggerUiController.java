package com.ll.hirehub.resume.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把 /swagger-ui.html 重定向到 webjar 的 Swagger UI 并指向本服务 /v3/api-docs。
 * 不依赖 springdoc 的 Swagger UI 自动配置——它在 Boot 3.5 下注册 /swagger-ui/**\/*xxx 这种非法 PathPattern 会崩溃。
 */
@Controller
public class SwaggerUiController {

    @GetMapping("/swagger-ui.html")
    public String swaggerUi() {
        return "redirect:/webjars/swagger-ui/5.32.14/index.html?url=/v3/api-docs";
    }
}