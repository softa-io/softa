package io.softa.framework.orm.service;

import java.util.List;
import java.util.Map;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;

/**
 * Whether an acting membership was minted for a consultant, read straight off the account row.
 *
 * <p>One home for a question two independent builds have to answer identically. The permission
 * snapshot and the FE's ui-context assemble the same principal from the same tables, and each used
 * to carry its own copy of this read — down to the driver coercion below. Two copies of one rule is
 * how the sidebar and the gate start disagreeing, which is the exact drift the ui-context builder's
 * own header warns about; the consultant principal is what that warning looked like when it was
 * missed the first time.
 *
 * <p>Here rather than in either starter because the two readers are deliberately independent of each
 * other, and this layer is the only one both already depend on — the same reason
 * {@link ConsultantAccessChecker} is declared next to it.
 *
 * <p>Read generically by model name, never through user-starter's typed services: the permission
 * engine also gates deployments that do not carry user-starter at all, and a missing model must
 * answer "not a consultant" rather than fail the build.
 */
public final class ConsultantMemberships {

    private static final String M_USER_ACCOUNT = "UserAccount";
    private static final String F_CONSULTANT = "consultant";

    private ConsultantMemberships() {
    }

    /**
     * @param modelService the generic model reader
     * @param userId       the acting membership (the request's {@code userId}); null → false
     * @return true when this membership carries the consultant flag
     */
    public static boolean isConsultant(ModelService<?> modelService, Long userId) {
        if (modelService == null || userId == null) {
            return false;
        }
        List<Map<String, Object>> rows = modelService.searchList(M_USER_ACCOUNT,
                new FlexQuery(List.of(F_CONSULTANT), new Filters().eq(ModelConstant.ID, userId)));
        if (rows.isEmpty()) {
            return false;
        }
        Object flag = rows.get(0).get(F_CONSULTANT);
        // Boolean or 1/0, depending on how the driver maps the column.
        return Boolean.TRUE.equals(flag) || (flag instanceof Number n && n.intValue() == 1);
    }
}
