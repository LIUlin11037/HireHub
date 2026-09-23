package com.ll.hirehub.auth.vo;

import lombok.Data;

import java.util.List;

/**
 * 身份总览（见 D-06 落地设计）。
 * <pre>
 * GET /api/auth/me/identities
 * → { "globalRoles": ["SEEKER"],
 *     "companies": [ { "companyId": 1, "companyName": "XX科技", "role": "HR", "verifyStatus": "通过" } ] }
 * </pre>
 * 这是前端身份切换器的<b>唯一</b>数据源：切换身份只改前端路由与 {@code X-Company-Id}，
 * 不调后端接口（有状态切换会让多端互相覆盖，且切换接口本身是提权面）。
 */
@Data
public class MeIdentitiesVO {

    /** 全局角色：SEEKER / PLATFORM_ADMIN（企业维度角色不在这里，见 D-04） */
    private List<String> globalRoles;

    /** 该账号在哪些企业里、以什么角色出现 */
    private List<CompanyIdentity> companies;

    @Data
    public static class CompanyIdentity {

        private Long companyId;
        private String companyName;

        /** 企业维度角色：OWNER / HR */
        private String role;

        /** 认证状态（中文标签，照 D-06 的契约） */
        private String verifyStatus;

        /** 认证状态码 0 待审核 / 1 通过 / 2 驳回 / 3 已撤销——给前端做样式用，避免用中文字符串做判断 */
        private Integer verifyStatusCode;
    }
}
