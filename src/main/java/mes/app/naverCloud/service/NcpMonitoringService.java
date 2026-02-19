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
import org.springframework.beans.factory.annotation.Required;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NcpMonitoringService {

    private final NcpAuthService ncpAuthService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final String API_URL = "https://cw.apigw.ntruss.com/cw_fea/real/cw/api/data/query/multiple";
    private final String API_PATH = "/cw_fea/real/cw/api/data/query/multiple";

    @Value("${ncp_api_cwKey}")
    private String cw_key;

    //ncp api를 통해 vm 사용량 받아오기
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
}
