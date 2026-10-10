package com.ll.hirehub.job.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件（见 D-41 与踩坑记录 #45）。
 * <p>
 * <b>为什么必须有它</b>：MyBatis-Plus 的 {@code selectPage} 在**没有**分页拦截器时
 * <b>不会报任何错</b>，而是：
 * <ul>
 *   <li>不拼 LIMIT/OFFSET —— 把<b>整表</b>查出来；</li>
 *   <li>{@code Page.getTotal()} 恒为 <b>0</b> —— 因为没人执行 COUNT 查询。</li>
 * </ul>
 * 于是「分页查询」静默退化成"查全表 + 前端看到总数 0"。这是一个<b>不报错的错</b>，
 * 也正是加 {@code /api/job/mine} 时才发现的：全项目 8 个服务<b>都没配</b>它
 * （因为此前没有一处用过 {@code selectPage}，分页都走 ES 的 {@code search_after}）。
 * <p>
 * 只有用到 {@code selectPage} 的服务需要加；本项目目前只有 job。
 * {@code PaginationInnerInterceptor} 依赖 jsqlparser 改写 COUNT 语句，
 * 见 pom 里的 {@code mybatis-plus-jsqlparser}。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
