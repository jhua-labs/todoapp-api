package com.todoapp.support;

import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

/**
 * 每个集成测试开始前：清空业务表、时钟复位到 {@link MutableClock#DEFAULT_INSTANT}。
 *
 * <p>顺序 {@value #ORDER}：排在 {@link TransactionalTestExecutionListener}（4000，开启测试事务）和
 * {@code SqlScriptsTestExecutionListener}（5000，执行 {@code @Sql} 播种）之前。因此
 * {@code @Sql} 播种的数据不会被清掉，清表也不会被卷进测试事务、随回滚失效。
 *
 * <p>在测试开始前而不是结束后做：上一个测试中途失败留下的数据也不会污染下一个测试。
 */
public class ResetTestStateListener extends AbstractTestExecutionListener {

    static final int ORDER = 3500;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        ApplicationContext spring = testContext.getApplicationContext();
        spring.getBean(DatabaseCleaner.class).clean();
        spring.getBean(MutableClock.class).reset();
    }
}
