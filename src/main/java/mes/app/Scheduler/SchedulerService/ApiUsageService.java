package mes.app.Scheduler.SchedulerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApiUsageService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SqlRunner sqlRunner;

    /**
     * 일일 사용량 이관 핵심 로직
     */
    @Transactional
    public void migrateMonthlyApiUsage() {
        log.info("전월 API 호출 집계 시작");

        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[스케줄러 감지] 현재 서버 시간: {} | 작업명: 전월 데이터 일괄 이관", currentTime);

        // 1. [수정 포인트] '어제' 대신 '지난달' 패턴 생성
        // 오늘이 2월이면 lastMonthPattern은 "202601"이 됩니다.
        String lastMonthPattern = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));

        // 2. SCAN 패턴 수정 (끝에 *를 붙여 해당 월의 모든 날짜 포함)
        // 패턴 예시: "MES:*:202601*"
        ScanOptions options = ScanOptions.scanOptions().match("MES:*:" + lastMonthPattern + "*").count(1000).build();

        Map<String, Long> summaryMap = new HashMap<>(); // 이 변수는 실제로는 사용 안 하겠네요 (일자별로 넣을 거니까)
        List<MapSqlParameterSource> batchList = new ArrayList<>();
        Set<String> keysToDelete = new HashSet<>();

        redisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next());
                    keysToDelete.add(key);

                    String[] parts = key.split(":");
                    String spjangcd = parts[1];
                    String dateStr = parts[2]; // yyyyMMdd 추출

                    Object val = redisTemplate.opsForValue().get(key);
                    long count = (val != null) ? Long.parseLong(val.toString()) : 0;

                    // [수정 포인트] 일자별로 Row를 만들기 위해 리스트에 추가
                    LocalDate rowDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                    batchList.add(new MapSqlParameterSource()
                            .addValue("stat_day", Date.valueOf(rowDate))
                            .addValue("spjangcd", spjangcd)
                            .addValue("total_count", count));
                }
            } catch (Exception e) { log.error("SCAN Error", e); }
            return null;
        });

        if (batchList.isEmpty()) return;

        // 3. SQL 및 실행 로직 (기존과 동일)
        String sql = "INSERT INTO api_log_entry (stat_day, spjangcd, total_count) " +
                "VALUES (:stat_day, :spjangcd, :total_count) " +
                "ON CONFLICT (stat_day, spjangcd) DO UPDATE SET total_count = EXCLUDED.total_count";

        SqlParameterSource[] batchArgs = batchList.toArray(new SqlParameterSource[0]);

        int[] result = sqlRunner.batchUpdate(sql, batchArgs);

        if (result.length > 0) {
            redisTemplate.delete(keysToDelete);
            log.info("[SaaS] {} 월분 {} 건 이관 성공", lastMonthPattern, result.length);
        }
    }

}
