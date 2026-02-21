package mes.app.naverCloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.naverCloud.Enum.NcpMetric;
import mes.app.naverCloud.dto.DataQueryRequest;
import mes.app.naverCloud.dto.NcpMetricResponse;
import mes.app.naverCloud.dto.NetworkChartDto;
import mes.app.naverCloud.strategy.MetricTimeRangeStrategy;
import mes.app.util.RedisService;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

import org.springframework.beans.factory.annotation.Required;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NcpMonitoringService {

    private final NcpAuthService ncpAuthService;
    private final RestTemplate restTemplate = new RestTemplate();

    private final RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper objectMapper;
    private final String API_URL = "https://cw.apigw.ntruss.com/cw_fea/real/cw/api/data/query/multiple";
    private final String API_PATH = "/cw_fea/real/cw/api/data/query/multiple";
    private final RedisService redisService;
    private final SqlRunner sqlRunner;

    @Value("${ncp_api_cwKey}")
    private String cw_key;

    //region: ncp api를 통해 vm 사용량 받아오기
    public String fetchMetrics(List<NcpMetric> metrics, String instanceNo, MetricTimeRangeStrategy timeRange){

        if(metrics.isEmpty()) return null;

        long fixedStartTime = timeRange.getStartTime();
        long fixedEndTime = System.currentTimeMillis();

        Map<String, Object> body = new HashMap<>();
        body.put("timeStart", fixedStartTime);
        body.put("timeEnd", fixedEndTime);
        body.put("productName", "System/Server(VPC)");

        List<DataQueryRequest> metricInfoList = metrics.stream().map(m -> {

            DataQueryRequest dto = new DataQueryRequest();
            dto.setCw_key(cw_key);
            dto.setMetric(m.name());
            dto.setInterval(m.getDefaultInterval());
            dto.setAggregation(m.getDefaultAggregation());
            dto.setInstanceDimension(instanceNo);
            return dto;

        }).collect(Collectors.toList());

        body.put("metricInfoList", metricInfoList);

        // 인증 및 전송
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpHeaders headers = ncpAuthService.createHeader(HttpMethod.POST, API_PATH, timestamp);

        return restTemplate.postForObject(API_URL, new HttpEntity<>(body, headers), String.class);

    }
    //endregion

    //region: ncp에서 vm 사용량 가져온거 평균값으로 환산
    /**
     * 메트릭 데이터를 가져와서 평균값(단일값)으로 반환
     */
    public Map<String, Double> fetchAverages(List<NcpMetric> metrics, String instanceNo, MetricTimeRangeStrategy timeRange){
        String jsonResponse = fetchMetrics(metrics, instanceNo, timeRange);

        if(jsonResponse == null) return Collections.emptyMap();

        try{

            List<NcpMetricResponse> responses = objectMapper.readValue(
                    jsonResponse, new TypeReference<List<NcpMetricResponse>>() {});

            return responses.stream().collect(Collectors.toMap(
                    NcpMetricResponse::getMetric,
                    response -> {
                        double avg = response.getAverageValue();
                        // 소수점 둘째 자리까지 반올림 (예: 12.3456 -> 12.35)
                        return Math.round(avg * 100.0) / 100.0;
                    }
            ));
        }catch(Exception e){
            log.error("NCP 데이터 파싱 오류", e);
            return Collections.emptyMap();
        }
    }
    //endregion

    //region : ncp에서 네트워크 사용량 가져오는 api 호출
    //Network 아웃바운드, 인바운드 가공 로직
    public NetworkChartDto fetchTrafficHistory(List<NcpMetric> metrics, String instanceNo, MetricTimeRangeStrategy timeRange){
        String jsonResponse = fetchMetrics(metrics, instanceNo, timeRange);
        NetworkChartDto chartDto = new NetworkChartDto();
        try{

            List<NcpMetricResponse> responses = objectMapper.readValue(
                    jsonResponse, new TypeReference<List<NcpMetricResponse>>() {});

            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd");

            for(NcpMetricResponse res : responses){
                List<Double> values = res.getDps().stream()
                        .map(dp -> NetworkChartDto.round(dp.get(1)))
                        .collect(Collectors.toList());

                if(res.getMetric().equals(NcpMetric.avg_rcv_bps.name())){
                    chartDto.setInboundData(values);
                    List<String> labels = res.getDps().stream()
                            .map(dp -> sdf.format(new Date(dp.get(0).longValue())))
                            .collect(Collectors.toList());
                    chartDto.setLabels(labels);
                }else{
                    chartDto.setOutboundData(values);
                }
            }

        }catch (Exception e) {
            log.error("네트워크 데이터 가공 중 오류", e);
        }
        return chartDto;
    }
    //endregion

    //region: 월별 고객사 가입현황
    //월별 가입현황 조회
    public List<Map<String, Object>> getMontlyRegisterList(String date){

        date = date.replaceAll("-", "");

        MapSqlParameterSource param = new MapSqlParameterSource();

        param.addValue("date", "%" + date + "%");

        String sql = """
                select spjangnm,
                spjangcd,
                CASE
                	WHEN "state" = 'O' THEN '승인'
                	WHEN "state" = 'X' THEN '미승인'
                	ELSE "state"
                END AS "state_text",
                subscriptiondate
                from
                tb_xa012
                where subscriptiondate like :date
                """;
        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

        return items;
    }
    //endregion

    //region: api 콜 횟수 (고객사별)
    //facade : 최종호출 로직 (api 콜 횟수)
    public List<Map<String, Object>> getApiCntListBySpjangcd(){

        //이번달 모든 사업장들의 api 콜 횟수
        Map<String, Object> thisMonthDataPaged = getThisMonthDataPaged(0, 10);
        List<Map<String, Object>> redisDataList = (List<Map<String, Object>>) thisMonthDataPaged.get("data");

        Map<String, Long> redisMap = redisDataList.stream()
                .collect(Collectors.toMap(
                        item -> String.valueOf(item.get("spjangcd")),
                        item -> (Long)item.getOrDefault("totalCount", 0L),
                        (existing, replacement) -> existing
                ));

        //RDB에서 가져온 사업장명과 사업장코드들
        List<Map<String, Object>> spjangList = getSpjangList();

        List<Map<String, Object>> ApicombinedList = new ArrayList<>();

        for(Map<String, Object> spjang : spjangList){
            String spjangCode = String.valueOf(spjang.get("spjangcd"));
            Integer apiCallLimit = Optional.ofNullable((Integer) spjang.get("api_call_limit")).orElse(0);

            String status = "";

            Long callCount = redisMap.getOrDefault(spjangCode, 0L);

            if(apiCallLimit - callCount > 0){
                status = "정상";
            }else{
                status = "초과";
            }

            spjang.put("apiCallCount", callCount);
            spjang.put("status", status);

            ApicombinedList.add(spjang);
        }
        return ApicombinedList;
    }


    //redis에서 고객사별로 api 콜 호출 횟수 조회 (페이징)
    //TODO: 분석필요
    private Map<String, Object> getThisMonthDataPaged(int page, int size) {
        // 1. 현재 날짜 정보 가져오기
        LocalDate today = LocalDate.now();
        String currentYearMonth = today.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int currentDay = today.getDayOfMonth(); // 오늘이 19일이면 19까지

        // 2. 모든 사업장 코드 추출 (MES:*:202602* 패턴으로 이번 달 데이터가 있는 사업장만 스캔)
        Set<String> allSpjangCodes = getSpjangCodesByScan(currentYearMonth);
        List<String> sortedCodes = allSpjangCodes.stream().sorted().collect(Collectors.toList());

        // 3. 페이징 처리
        int totalCount = sortedCodes.size();
        int fromIndex = Math.min(page * size, totalCount);
        int toIndex = Math.min(fromIndex + size, totalCount);

        if (fromIndex >= totalCount) return Map.of("data", Collections.emptyList(), "total", 0);
        List<String> pagedCodes = sortedCodes.subList(fromIndex, toIndex);

        // 4. 파이프라인으로 이번 달 1일부터 오늘까지의 데이터만 '딱 한 번' 통신
        List<Object> rawResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String code : pagedCodes) {
                for (int day = 1; day <= currentDay; day++) {
                    String key = "MES:" + code + ":" + currentYearMonth + String.format("%02d", day);
                    connection.stringCommands().get(key.getBytes());
                }
            }
            return null;
        });

        // 5. 결과 가공 (사업장별로 1일~오늘치 합산)
        List<Map<String, Object>> finalData = new ArrayList<>();
        int resultIdx = 0;
        for (String code : pagedCodes) {
            long monthlySum = 0;
            for (int j = 0; j < currentDay; j++) {
                Object val = rawResults.get(resultIdx++);
                if (val != null) {
                    try {
                        // Redis에서 꺼낸 값(byte[] 또는 String)을 숫자로 변환
                        monthlySum += Long.parseLong(val.toString());
                    } catch (Exception e) { /* 숫자가 아니면 무시 */ }
                }
            }
            Map<String, Object> row = new HashMap<>();
            row.put("spjangcd", code);
            row.put("totalCount", monthlySum);
            finalData.add(row);
        }

        return Map.of(
                "data", finalData,
                "total", totalCount,
                "page", page,
                "currentMonth", currentYearMonth
        );
    }


    // 이번 달 키가 존재하는 사업장만 찾는 스캔 메서드
    private Set<String> getSpjangCodesByScan(String yearMonth) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> codes = new HashSet<>();
            // 패턴을 "MES:*:202602*" 로 주면 이번 달 데이터가 있는 사업장만 필터링됩니다.
            ScanOptions options = ScanOptions.scanOptions().match("MES:*:" + yearMonth + "*").count(1000).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next());
                    String[] parts = key.split(":");
                    if (parts.length >= 2) codes.add(parts[1]);
                }
            }
            return codes;
        });
    }


    // 계약 고객사 현황 리스트
    private List<Map<String, Object>> getSpjangList(){

        MapSqlParameterSource param = new MapSqlParameterSource();

        String sql = """
                select
                a.spjangnm,
                a.spjangcd,
                b.api_call_limit
                from
                tb_xa012 a
                left join bill_plans b
                on a.bill_plans_id = b.id
                where "state" = 'O'
                """;
        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

        return items;
    }
    //endregion



    /**
     * 로컬 캐시에 임시 저장된 데이터를 Redis로 일괄 합산(Sync)
     */
    public void syncCacheToDb(){

        Map<String, Object> localCache = redisService.getLocalCache();

        if(localCache.isEmpty()){
            log.info("Redis로 이관할 데이터가 없습니다.");
            return;
        }

        int successCnt = 0;
        List<String> syncedKeys = new ArrayList<>();

        for(Map.Entry<String, Object> entry : localCache.entrySet()){
            String key = entry.getKey();
            Long value = Long.valueOf(entry.getValue().toString());

            try{
                redisService.incrementValue(key, value);

                syncedKeys.add(key);

            }catch(Exception e){
                log.error("Redis 이관 실패",key, e.getMessage());
                break;
            }
        }

        syncedKeys.forEach(localCache::remove);

        if (!syncedKeys.isEmpty()) {
            log.info("[Sync] 총 {} 건의 데이터가 Redis로 합산 완료되었습니다.", syncedKeys.size());
        }
    }
}
