package com.ll.hirehub.job.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把现有职位 ID 灌进布隆过滤器。
 * <p>
 * 布隆没有「漏报」的前提是：位图必须包含所有合法 ID。
 * 所以新职位在创建时 {@code bloom.add}，历史职位在启动时全量回灌。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterInitializer implements ApplicationRunner {

    private final JobMapper jobMapper;
    private final BloomFilter bloomFilter;

    @Override
    public void run(ApplicationArguments args) {
        List<Job> jobs = jobMapper.selectList(new LambdaQueryWrapper<Job>()
                .select(Job::getId));
        jobs.forEach(j -> bloomFilter.add(j.getId()));
        log.info("布隆过滤器初始化完成，回灌 {} 个职位 ID", jobs.size());
    }
}
