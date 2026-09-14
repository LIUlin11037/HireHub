package com.ll.hirehub.auth.util;

/**
 * 身份证号校验位算法（GB 11643-1999 / ISO 7064 MOD 11-2）
 * 不依赖任何外部接口，可真实校验 18 位身份证号是否"编造"
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
