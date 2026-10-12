package com.abc.sourceservice.service;

import com.abc.sourceservice.entity.SourceRule;
import com.abc.sourceservice.engine.RuleExecutionConfig;
import com.abc.sourceservice.mapper.SourceRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 采集规则的 CRUD 与查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceRuleService {

    private final SourceRuleMapper mapper;

    public List<SourceRule> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<SourceRule>()
                .eq(SourceRule::getEnabled, 1)
                .orderByDesc(SourceRule::getWeight));
    }

    public List<SourceRule> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<SourceRule>()
                .orderByDesc(SourceRule::getWeight));
    }

    public SourceRule getById(Long id) {
        return mapper.selectById(id);
    }

    public SourceRule getByName(String name) {
        return mapper.selectOne(new LambdaQueryWrapper<SourceRule>()
                .eq(SourceRule::getName, name).last("LIMIT 1"));
    }

    public RuleExecutionConfig toConfig(SourceRule rule) {
        return RuleExecutionConfig.of(rule);
    }

    public SourceRule create(SourceRule rule) {
        rule.setId(null);
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getWeight() == null) rule.setWeight(0);
        if (rule.getSuccessRate() == null) rule.setSuccessRate(0.0);
        if (rule.getHealthStatus() == null) rule.setHealthStatus("UNKNOWN");
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        mapper.insert(rule);
        return rule;
    }

    public SourceRule update(SourceRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(rule);
        return mapper.selectById(rule.getId());
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }

    /** 记录一次执行结果，更新源健康度（简单滑动统计）。 */
    public void markHealth(Long ruleId, boolean success) {
        SourceRule rule = mapper.selectById(ruleId);
        if (rule == null) return;
        double old = rule.getSuccessRate() == null ? 0.0 : rule.getSuccessRate();
        double rate = old * 0.8 + (success ? 0.2 : 0.0); // EMA
        rule.setSuccessRate(rate);
        rule.setHealthStatus(rate > 0.7 ? "UP" : (rate > 0.3 ? "DEGRADED" : "DOWN"));
        rule.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(rule);
    }
}
