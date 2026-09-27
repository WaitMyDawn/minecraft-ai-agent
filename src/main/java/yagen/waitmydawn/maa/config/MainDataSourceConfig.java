package yagen.waitmydawn.maa.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 主数据源配置 (knowledge.mv.db) — 项目唯一数据源，标记 @Primary。
 */
@Configuration
@EnableJpaRepositories(
        basePackages = {"yagen.waitmydawn.maa.model", "yagen.waitmydawn.maa.service"},
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class MainDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.driverClassName:org.h2.Driver}")
    private String driverClassName;

    @Value("${spring.datasource.username:sa}")
    private String username;

    @Value("${spring.datasource.password:}")
    private String password;

    /**
     * 连接池大小。
     *
     * <p>注意：{@code spring.datasource.hikari.*} 那套配置在这里<b>不生效</b> —— 这个 DataSource
     * 是手写的 {@code new HikariDataSource()}，不是 Spring Boot 自动配置出来的，所以池参数只能在代码里设。
     * 默认的 10 个连接在多用户并发写对话/消息时会排队，拿不到连接要等 30 秒才抛错。
     */
    @Value("${maa.db.pool-size:20}")
    private int poolSize;

    /** 拿连接的等待上限（毫秒）：宁可快速失败并给出明确错误，也不要让请求挂 30 秒 */
    @Value("${maa.db.connection-timeout-ms:10000}")
    private long connectionTimeoutMs;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDriverClassName(driverClassName);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("maa-hikari");
        ds.setMaximumPoolSize(Math.max(2, poolSize));
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(Math.max(250, connectionTimeoutMs));
        return ds;
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("yagen.waitmydawn.maa.model");
        em.setPersistenceUnitName("mainPU");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(true);
        vendorAdapter.setShowSql(false);
        em.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(
            jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
