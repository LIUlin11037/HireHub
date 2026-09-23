package com.ll.hirehub.common.util;

/**
 * 身份证号校验位算法（GB 11643-1999 / ISO 7064 MOD 11-2）。
 * <p>
 * 纯算法、无外部依赖，所以从 auth 移到 common 供多服务复用——
 * 三层核验里「法人授权」也需要校验法人身份证（见 D-24）。
 */
public final class IdCardUtil {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCardUtil() {
    }

    /** 校验 18 位身份证号的校验位是否正确 */
    public static boolean isValid(String idCard) {
        if (idCard == null || idCard.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = idCard.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * WEIGHTS[i];
        }
        char expect = CHECK_CODES[sum % 11];
        char actual = idCard.charAt(17);
        return expect == Character.toUpperCase(actual);
    }
}
