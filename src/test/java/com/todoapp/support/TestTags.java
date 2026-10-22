package com.todoapp.support;

/** JUnit 标签常量。surefire 的 groups / excludedGroups 用的就是这些字符串。 */
public final class TestTags {

    /** 只有部署到 staging 后才能证明的断言；PR / 本地默认排除，且不计入开发期覆盖门槛。 */
    public static final String NEEDS_STAGING = "needs-staging";

    private TestTags() {
    }
}
