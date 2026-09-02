package com.meiyun.common.dualsign;

import java.util.List;

/**
 * 双签校验未通过时抛出，携带全部违规项（系统级硬校验，非提示）。
 */
public class DualSignException extends RuntimeException {

    private final List<String> violations;

    public DualSignException(List<String> violations) {
        super("双签校验未通过: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
