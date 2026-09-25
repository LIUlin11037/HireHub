package com.ll.hirehub.company.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 企业核验（D-18：Mock 打底、可切阿里云）。
 * <p>
 * 它有两件事必须成立：
 * 1. 信用代码校验位是真算法，编造的号要能挡住；
 * 2. 能返回"注销"这个状态 —— 否则 Q-08 的复核分支根本没法端到端验证，
 *    只能靠读代码（这正是当初加 {@code -REVOKED} 约定测试夹具的原因）。
 */
class MockCompanyVerifierTest {

    private static final String VALID_CREDIT_CODE = "913100001234567896";

    private final MockCompanyVerifier verifier = new MockCompanyVerifier();

    @Test
    @DisplayName("正常企业：valid=true、经营状态=存续")
    void normalCompanyIsValidAndActive() {
        CompanyVerifyResult r = verifier.verify("某科技有限公司", VALID_CREDIT_CODE, "张三");

        assertThat(r.isValid()).isTrue();
        assertThat(r.getBusinessStatus()).isEqualTo("存续");
    }

    @Test
    @DisplayName("★ 名字以 -REVOKED 结尾 → 经营状态=注销、valid=false（Q-08 的测试夹具约定）")
    void nameEndingWithRevokedSuffixReportsDeregistered() {
        CompanyVerifyResult r = verifier.verify("某科技有限公司-REVOKED", VALID_CREDIT_CODE, "张三");

        assertThat(r.isValid()).isFalse();
        assertThat(r.getBusinessStatus()).isEqualTo(MockCompanyVerifier.STATUS_REVOKED);
    }

    @Test
    @DisplayName("★ 名字含「已注销」→ 同样报注销（给人工演示用的中文写法）")
    void nameContainingRevokedKeywordReportsDeregistered() {
        CompanyVerifyResult r = verifier.verify("某科技（已注销）有限公司", VALID_CREDIT_CODE, "张三");

        assertThat(r.isValid()).isFalse();
        assertThat(r.getBusinessStatus()).isEqualTo(MockCompanyVerifier.STATUS_REVOKED);
    }

    @Test
    @DisplayName("★ 信用代码校验位错误 → 拒绝，且不得误报成「注销」")
    void invalidCreditCodeIsRejectedAsInvalidNotDeregistered() {
        CompanyVerifyResult r = verifier.verify("某科技有限公司", "913100001234567890", "张三");

        assertThat(r.isValid()).isFalse();
        assertThat(r.getMessage()).contains("信用代码");
        // 这是两条完全不同的分支：代码错误 ≠ 企业已注销。
        // 混在一起会让复核逻辑把"接口给了个坏参数"当成"企业注销了"，进而误下线岗位。
        assertThat(r.getBusinessStatus()).isNull();
    }
}
