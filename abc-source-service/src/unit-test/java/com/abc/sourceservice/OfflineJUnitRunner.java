package com.abc.sourceservice;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * 离线环境下的 JUnit5 启动器。
 *
 * <p>为什么不用 {@code mvn test}：交付机房的 Maven 本地仓库没有缓存
 * surefire 2.22.x 与 junit-jupiter 的 jar，离线（{@code -o}）下无法解析；
 * 但同时仓库里又有 junit 5.8.2 的运行时 jar。于是这里绕开 surefire，
 * 直接用 junit-platform-launcher 驱动 Jupiter 引擎，
 * 由 {@code run-unit-tests.ps1} 负责拼 classpath 并调用本类。
 *
 * <p>测试本身仍是标准 JUnit5 用例（{@code @Test} + 断言），
 * 在联网机器上执行 {@code mvn -Punit-tests test} 效果等价。
 */
public final class OfflineJUnitRunner {

    private static final String[] TEST_CLASSES = {
            "com.abc.sourceservice.select.SourceRankerTest",
            "com.abc.sourceservice.select.SelectPipelineTest",
            "com.abc.sourceservice.security.SsrfGuardTest",
            "com.abc.sourceservice.security.ProxySignerTest"
    };

    public static void main(String[] args) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        for (String className : TEST_CLASSES) {
            builder.selectors(selectClass(className));
        }
        LauncherDiscoveryRequest request = builder.build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        summary.printTo(out);
        summary.printFailuresTo(out);
        System.out.println("RESULT total=" + summary.getTestsFoundCount()
                + " succeeded=" + summary.getTestsSucceededCount()
                + " failed=" + summary.getTestsFailedCount()
                + " skipped=" + summary.getTestsSkippedCount());
        System.exit(summary.getTotalFailureCount() > 0 ? 1 : 0);
    }
}
