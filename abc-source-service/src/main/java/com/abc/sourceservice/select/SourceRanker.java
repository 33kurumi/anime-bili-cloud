package com.abc.sourceservice.select;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多源候选「五级比较器」。
 *
 * <p>设计来源：Animeko 的 MediaSelector 用一串 comparator 逐级比较候选
 * （优先级 → 缓存命中 → 速度 → 空闲度 → 随机），本项目把它落到“资源站采集”场景：
 * <pre>
 *   第 1 级 weight        降序   运营手工配的源权重（sql/source_rule.weight）
 *   第 2 级 successRate   降序   历史成功率 EMA（SourceRuleService.markHealth 维护）
 *   第 3 级 responseTime  升序   最近一次该源搜索耗时（毫秒，未知排最后）
 *   第 4 级 healthStatus  UP > UNKNOWN > DEGRADED > DOWN
 *   第 5 级 随机          打散    避免同分时永远是同一个源吃满流量（等效负载均衡）
 * </pre>
 *
 * <p>为什么不把“标题相似度”放进比较链？相似度是<b>准入条件</b>（>= 阈值才允许进候选池，
 * 见 SourceSelectService），进了候选池之后比的是“这个源靠不靠谱”。
 * 相似度仍会以 0.45 的权重进入 rankScore，用于前端展示与人工排查。
 *
 * <p>rankReason 是答辩时的“可视化证据”：前端每换一次源，用户就能看到
 * “为什么这次选了这个源”，而不是黑盒。
 */
@Slf4j
@Component
public class SourceRanker {

    /** 排序输入：一个候选 + 它所属源的运行指标。 */
    public record RankInput(Long ruleId,
                            String sourceName,
                            String matchedTitle,
                            String directUrl,
                            String episodePageUrl,
                            double matchScore,
                            int weight,
                            double successRate,
                            long responseTimeMs,
                            String health) { }

    /** 五级比较器本体（第 5 级用一次性随机数，保证同分随机打散）。 */
    private static final Comparator<RankedCandidate> ORDER =
            Comparator.<RankedCandidate>comparingInt(RankedCandidate::weight).reversed()
                    .thenComparing(Comparator.<RankedCandidate>comparingDouble(RankedCandidate::successRate).reversed())
                    .thenComparingLong(c -> c.responseTimeMs() <= 0 ? Long.MAX_VALUE : c.responseTimeMs())
                    .thenComparingInt(c -> healthRank(c.health()))
                    .thenComparingDouble(RankedCandidate::tieBreaker);

    /** 排序并回填名次 + rankReason。 */
    public List<RankedCandidate> rank(List<RankInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<RankedCandidate> out = new ArrayList<>(inputs.size());
        for (RankInput in : inputs) {
            if (in == null) continue;
            int weight = in.weight();
            double rate = clamp01(in.successRate());
            long rt = in.responseTimeMs();
            String health = normalizeHealth(in.health());
            double score = compositeScore(in.matchScore(), weight, rate, health, rt);

            List<String> reasons = new ArrayList<>(7);
            reasons.add("权重=" + weight + "（第1级：权重降序）");
            reasons.add("成功率=" + fmt(rate) + "（第2级：成功率降序）");
            reasons.add((rt > 0 ? "响应=" + rt + "ms" : "响应=未知") + "（第3级：响应时间升序）");
            reasons.add("健康=" + health + "（第4级：UP>UNKNOWN>DEGRADED>DOWN）");
            reasons.add("综合分=" + fmt2(score));
            reasons.add("第5级：同分随机打散");

            out.add(new RankedCandidate(in.ruleId(), in.sourceName(), in.matchedTitle(),
                    in.directUrl(), in.episodePageUrl(), clamp01(in.matchScore()),
                    weight, rate, rt, health, score, List.copyOf(reasons),
                    ThreadLocalRandom.current().nextDouble()));
        }
        out.sort(ORDER);
        List<RankedCandidate> ranked = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            ranked.add(out.get(i).withRank(i + 1, out.size()));
        }
        return ranked;
    }

    /** 单候选排序（指定源场景）。 */
    public RankedCandidate rankOne(RankInput input) {
        List<RankedCandidate> ranked = rank(List.of(input));
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * 综合分（0~100，仅用于展示/人工排查，不参与比较链）。
     * 45% 标题相似度 + 25% 权重 + 20% 成功率 + 10% 健康度，再乘一个最多 ±15% 的速度因子。
     */
    static double compositeScore(double matchScore, int weight, double successRate,
                                 String health, long responseTimeMs) {
        double weightNorm = Math.min(1.0, Math.max(0, weight) / 20.0);
        double healthFactor = switch (healthRank(health)) {
            case 0 -> 1.0;   // UP
            case 1 -> 0.8;   // UNKNOWN
            case 2 -> 0.5;   // DEGRADED
            default -> 0.2;  // DOWN
        };
        double rtFactor = responseTimeMs <= 0
                ? 0.6
                : Math.max(0.0, 1.0 - Math.min(responseTimeMs, 20_000L) / 20_000.0);
        double base = 0.45 * clamp01(matchScore)
                + 0.25 * weightNorm
                + 0.20 * clamp01(successRate)
                + 0.10 * healthFactor;
        double score = base * (0.85 + 0.15 * rtFactor);
        return Math.round(score * 10_000.0) / 100.0;
    }

    static int healthRank(String health) {
        String h = normalizeHealth(health);
        return switch (h) {
            case "UP", "HEALTHY" -> 0;
            case "UNKNOWN" -> 1;
            case "DEGRADED" -> 2;
            case "DOWN" -> 3;
            default -> 1;
        };
    }

    static String normalizeHealth(String health) {
        if (health == null || health.isBlank()) return "UNKNOWN";
        return health.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
