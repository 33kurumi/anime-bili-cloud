package com.abc.danmakuservice;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * 离线环境下的 JUnit5 启动器（弹幕服务）。
 *
 * <p>交付机房是离线环境：Maven 本地仓库（D:\\maven3.6.1\\repository）没有缓存
 * junit-jupiter 与 surefire 2.22.x，直接 {@code mvn test} 会在 test-compile 阶段失败。
 * 因此测试源码放在 {@code src/unit-test/java}（默认构建不编译），
 * 由 {@code run-unit-tests.ps1} 用 javac + junit-platform-launcher 直接驱动 Jupiter。
 *
 * <p>联网机器上仍可 {@code mvn -Punit-tests test} 走标准 surefire，用例完全一致。
 */
public final class OfflineJUnitRunner {

    private static final String[] TEST_CLASSES = {
            "com.abc.danmakuservice.cache.DanmakuLocalCacheTest",
            "com.abc.danmakuservice.service.DanmakuRateLimiterTest",
            "com.abc.danmakuservice.source.dandanplay.DanmakuEpisodeMatcherTest",
            "com.abc.danmakuservice.util.DanmakuTextNormalizerTest"
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
