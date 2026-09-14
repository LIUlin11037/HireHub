package com.ll.hirehub.company.util;

/**
 * 统一社会信用代码校验码算法（GB 32100-2015，mod 31）
 * 与身份证一样可真实实现，不依赖任何外部接口。
 */
public final class CreditCodeUtil {

    /** 31 位字符集（排除 I / O / Z / S / V） */
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    private CreditCodeUtil() {
    }

    /** 校验 18 位统一社会信用代码的校验位是否正确 */
    public static boolean isValid(String code) {
        if (code == null || code.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int idx = ALPHABET.indexOf(code.charAt(i));
            if (idx < 0) {
                return false; // 含非法字符
            }
            sum += idx * WEIGHTS[i];
        }
        int check = (31 - sum % 31) % 31;
        return ALPHABET.charAt(check) == code.charAt(17);
    }
}
