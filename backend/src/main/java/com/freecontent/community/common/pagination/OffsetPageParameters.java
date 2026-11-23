package com.freecontent.community.common.pagination;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import java.math.BigInteger;
import java.util.List;

public final class OffsetPageParameters {
    private OffsetPageParameters() { }

    public static BigInteger page(String raw) {
        return positiveInteger(raw, "page", 1, null);
    }

    public static int pageSize(String raw) {
        return positiveInteger(raw, "pageSize", 20, 100).intValueExact();
    }

    private static BigInteger positiveInteger(String raw, String field, int defaultValue, Integer maximum) {
        if (raw == null) return BigInteger.valueOf(defaultValue);
        if (!raw.matches("-?[0-9]+")) throw invalid(ErrorCode.MALFORMED_REQUEST, field, "必须为整数");
        var value = new BigInteger(raw);
        if (value.signum() <= 0 || maximum != null && value.compareTo(BigInteger.valueOf(maximum)) > 0) {
            String reason = maximum == null ? "必须大于等于1" : "必须在1到" + maximum + "之间";
            throw invalid(ErrorCode.VALIDATION_ERROR, field, reason);
        }
        return value;
    }

    private static BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, "查询参数不合法", null, null, List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
