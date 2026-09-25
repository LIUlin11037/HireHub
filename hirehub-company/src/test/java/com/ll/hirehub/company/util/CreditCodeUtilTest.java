package com.ll.hirehub.company.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一社会信用代码校验位（GB 32100）。
 * <p>
 * 这是本项目里少见的**真算法**（不是 mock），也是最该被单测覆盖的一段：
 * 它承担"识别编造的信用代码"这件事，一旦算错，非法企业就能通过核验。
 */
class CreditCodeUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "913100001234567896",     // 项目文档里的测试值
            "91330100297663536K",     // phase3 实测里生成出来的
            "91330100601719002L",
            "91330100679437798H"
    })
    @DisplayName("真实校验位正确的代码应通过")
    void acceptsValidCodes(String code) {
        assertThat(CreditCodeUtil.isValid(code)).isTrue();
    }

    @Test
    @DisplayName("★ 校验位被改掉必须被拒（否则随便编一个号就能建企业）")
    void rejectsFlippedCheckDigit() {
        // 913100001234567896 的正确校验位是 6，逐个换掉都应失败
        for (char c : "0123456789ABCDEFGHJKLMNPQRTUWXY".toCharArray()) {
            if (c == '6') {
                continue;
            }
            String bad = "91310000123456789" + c;
            assertThat(CreditCodeUtil.isValid(bad)).as("check digit %s", c).isFalse();
        }
    }

    @Test
    @DisplayName("长度不是 18 位一律拒绝")
    void rejectsWrongLength() {
        assertThat(CreditCodeUtil.isValid("91310000123456789")).isFalse();    // 17 位
        assertThat(CreditCodeUtil.isValid("9131000012345678961")).isFalse();  // 19 位
        assertThat(CreditCodeUtil.isValid("")).isFalse();
    }

    @Test
    @DisplayName("null 与非法字符不得抛异常，只返回 false")
    void rejectsNullAndIllegalChars() {
        assertThat(CreditCodeUtil.isValid(null)).isFalse();
        assertThat(CreditCodeUtil.isValid("9131000012345678$6")).isFalse();
    }
}
