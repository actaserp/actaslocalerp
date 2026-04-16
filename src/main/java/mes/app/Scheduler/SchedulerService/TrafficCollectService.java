package mes.app.Scheduler.SchedulerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.TrafficDailyEndpoint;
import mes.domain.entity.TrafficDailyUsage;
import mes.domain.repository.TrafficDailyEndpointRepository;
import mes.domain.repository.TrafficDailyUsageRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static mes.app.traffic.util.TrafficUtil.toGb;
import static mes.app.traffic.util.TrafficUtil.toMb;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficCollectService {

    private final RedisTemplate<String, String> redisTemplate;
    private final TrafficDailyUsageRepository usageRepository;
    private final TrafficDailyEndpointRepository endpointRepository;

    @Transactional
    public void collectYesterdayTrafficByRedis() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String dateSuffix = yesterday.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        log.info("======================================================");
        log.info("[트래픽 수집 스케줄러] 시작 - 대상날짜: {}", yesterday);
        log.info("======================================================");

        Set<String> keys = redisTemplate.keys("*:STATS:*:" + dateSuffix);

        if (keys == null || keys.isEmpty()) {
            log.info("[트래픽 수집] 수집할 Redis 키가 없습니다. (대상 패턴: *:STATS:*:{})", dateSuffix);
            return;
        }

        log.info("[트래픽 수집] 총 {}개의 키를 발견했습니다.", keys.size());

        int successCount = 0;
        int failCount = 0;

        for (String key : keys) {
            try {
                log.info("[트래픽 수집] 키 처리 중: {}", key);

                String[] parts = key.split(":");
                if (parts.length != 4) {
                    log.error("[트래픽 수집] 잘못된 키 형식: {}", key);
                    failCount++;
                    continue;
                }

                String service = parts[0];
                String company = parts[2];

                Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
                if (data.isEmpty()) {
                    log.warn("[트래픽 수집] 키에 데이터가 비어있습니다: {}", key);
                    failCount++;
                    continue;
                }

                // 1. 상위 테이블 (Usage) 처리
                long totalCount = parseLong(data, "total_count");
                long totalBytes = parseLong(data, "total_bytes");

                TrafficDailyUsage usage = usageRepository
                        .findByServiceAndCompanyAndDate(service, company, yesterday)
                        .orElseGet(() -> {
                            log.debug("[트래픽 수집] 신규 Usage 엔티티 생성: {}/{}", service, company);
                            return TrafficDailyUsage.builder()
                                    .service(service)
                                    .company(company)
                                    .date(yesterday)
                                    .build();
                        });

                usage.updateStats(totalCount, totalBytes, toMb(totalBytes), toGb(totalBytes));
                usage = usageRepository.save(usage);
                Long usageId = usage.getId();

                // 2. 하위 테이블 (Endpoint) 처리
                Map<String, Map<String, Long>> endpointMap = parseEndpoints(data);
                log.info("[트래픽 수집] {}/{} - 엔드포인트 {}건 파싱 완료", service, company, endpointMap.size());

                for (Map.Entry<String, Map<String, Long>> entry : endpointMap.entrySet()) {
                    String endpoint = entry.getKey();
                    Map<String, Long> stats = entry.getValue();

                    TrafficDailyEndpoint ep = endpointRepository
                            .findByServiceAndCompanyAndDateAndEndpoint(service, company, yesterday, endpoint)
                            .orElse(TrafficDailyEndpoint.builder()
                                    .service(service)
                                    .company(company)
                                    .date(yesterday)
                                    .endpoint(endpoint)
                                    .build());

                    ep.setUsageId(usageId);
                    ep.setCount(stats.getOrDefault("count", 0L));
                    ep.setBytes(stats.getOrDefault("bytes", 0L));
                    ep.setElapsed(stats.getOrDefault("elapsed", 0L));

                    endpointRepository.save(ep);
                }

                // 3. 수집 완료된 Redis 키 삭제
                redisTemplate.delete(key);
                log.info("[트래픽 수집] 성공: {} -> RDB 저장 및 Redis 키 삭제 완료", key);
                successCount++;

            } catch (Exception e) {
                failCount++;
                log.error("[트래픽 수집] 키 처리 중 치명적 오류 발생: {}", key);
                log.error("[트래픽 수집] 에러 메시지: {}", e.getMessage());
            }
        }

        log.info("======================================================");
        log.info("[트래픽 수집] 작업 완료 요약");
        log.info(" - 대상 날짜: {}", yesterday);
        log.info(" - 총 처리 키: {}", keys.size());
        log.info(" - 성공: {}개", successCount);
        log.info(" - 실패: {}개", failCount);
        log.info("======================================================");
    }

    private Map<String, Map<String, Long>> parseEndpoints(Map<Object, Object> data) {
        Map<String, Map<String, Long>> result = new HashMap<>();

        for (Map.Entry<Object, Object> entry : data.entrySet()) {
            String field = entry.getKey().toString();

            // total_count, total_bytes 등 기본 필드는 제외
            if (field.startsWith("total_")) continue;
            if (!field.contains(":")) continue;

            int lastColon = field.lastIndexOf(":");
            String endpoint = field.substring(0, lastColon); // 예: /api/logo
            String statKey = field.substring(lastColon + 1); // 예: count, bytes, elapsed

            result.computeIfAbsent(endpoint, k -> new HashMap<>())
                    .put(statKey, parseLong(entry.getValue().toString()));
        }
        return result;
    }

    private long parseLong(Map<Object, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? parseLong(val.toString()) : 0L;
    }

    private long parseLong(String val) {
        try {
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0L;
        }
    }
}
