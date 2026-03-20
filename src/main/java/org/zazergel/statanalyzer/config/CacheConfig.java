package org.zazergel.statanalyzer.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Конфигурация кеширования для оптимизации запросов к базе данных.
 * Использует Caffeine как провайдер кеша с разными стратегиями для разных типов данных.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Настраивает CacheManager с различными регионами кеша.
     *
     * @return настроенный CacheManager
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Кеш для snapshots - хранится до очистки данных
        cacheManager.registerCustomCache("snapshots",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Кеш для activity/locks - данные snapshot неизменны после загрузки
        cacheManager.registerCustomCache("snapshotData",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(1, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        // Кеш для victims - может раскрываться часто
        cacheManager.registerCustomCache("victims",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        return cacheManager;
    }
}
