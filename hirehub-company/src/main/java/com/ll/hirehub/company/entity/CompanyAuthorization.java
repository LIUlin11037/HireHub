package com.ll.hirehub.company.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 法人授权令牌（见 D-24 三层核验之第三层）。
 * <p>
 * 核心洞察：第三层验证的本质是「**法人主动授权**」，不是「姓名比对」——
 * 查到法人姓名只用于知道该找谁授权；授权来自法人本人完成实名并点击同意，是强证据，
 * 彻底消除中文重名导致的冒名接管问题。
 * <p>
 * 安全要点：令牌足够随机（防猜测）、一次性 + 有效期、**法人必须完成实名才能授权**、全程留档。
 */
@Data
@TableName("company_authorization")
public class CompanyAuthorization {

    /** 待授权 */
    public static final int STATUS_PENDING = 0;
    /** 已授权 */
    public static final int STATUS_AUTHORIZED = 1;
    /** 已过期 */
    public static final int STATUS_EXPIRED = 2;
    /** 已撤销 */
    public static final int STATUS_REVOKED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private String token;
    private String legalPersonName;
    private Integer status;
    /** 法人完成实名时的姓名（不落身份证号，见 D-18 敏感信息要求） */
    private String legalPersonRealName;
    private LocalDateTime expireTime;
    private LocalDateTime authorizeTime;
    private LocalDateTime createTime;
}
