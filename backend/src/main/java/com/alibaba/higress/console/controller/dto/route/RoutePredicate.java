package com.alibaba.higress.console.controller.dto.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePredicate {

    /**
     * see RoutePredicatesTypeEnum
     */
    private String matchType;

    private String matchValue;

    private Boolean caseSensitive;
}
