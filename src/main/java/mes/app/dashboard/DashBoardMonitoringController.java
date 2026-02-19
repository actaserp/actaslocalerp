package mes.app.dashboard;


import mes.app.naverCloud.Enum.NcpMetric;
import mes.app.naverCloud.dto.NetworkChartDto;
import mes.app.naverCloud.service.NcpMonitoringService;
import mes.app.naverCloud.strategy.MonthlyRange;
import mes.app.naverCloud.strategy.RealTimeRange;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/monitoring")
public class DashBoardMonitoringController {

    @Value("${ncp_api_instanceNo}")
    private String instanceNo;

    @Autowired
    NcpMonitoringService ncpMonitoringService;

    @GetMapping("/read")
    public AjaxResult GetDataList(){

        // CPU와 RAM 메트릭을 '실시간(30분)' 정책으로 묶어서 단일 시간 요청
        Map<String, Double> resourceSummary = ncpMonitoringService.fetchAverages(
                List.of(NcpMetric.avg_cpu_used_rto, NcpMetric.mem_usert),
                instanceNo,
                new RealTimeRange()
        );

        NetworkChartDto trafficHistory = ncpMonitoringService.fetchTrafficHistory(
                List.of(NcpMetric.avg_snd_bps, NcpMetric.avg_rcv_bps), "127900112"
                ,new MonthlyRange()
        );

        Map<String, Object> dataList = new HashMap<>();
        dataList.put("resource", resourceSummary);
        dataList.put("traffic", trafficHistory);

        return AjaxResult.success(null, dataList);
    }
}
