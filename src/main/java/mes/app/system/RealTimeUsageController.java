package mes.app.system;


import mes.app.system.service.RealTimeUsageService;
import mes.app.util.RedisService;
import mes.domain.model.AjaxResult;
import org.apache.poi.hpsf.Decimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/realtime")
public class RealTimeUsageController {

    @Autowired
    RedisService redisService;

    @Autowired
    RealTimeUsageService realTimeUsageService;

    //TODO: redis 데이터를 스케줄러로 얼마주기로 삭제하는지 확인필요 : runApiUsageMigration
    @GetMapping("/read")
    public AjaxResult getList(@RequestParam String spjangcd){

        String currentMonth = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMM"));


        String myPattern = "MES:" + spjangcd + ":" + currentMonth + "*";

        //redis 데이터
        Map<String, Integer> redisData = redisService.getValuesByPattern(myPattern);

        //RDB 데이터
        List<Map<String, Object>> usageList = realTimeUsageService.getUsageList(spjangcd);

        //이번 달 총 사용량 합산
        int totalRealTimeCnt = redisData.values().stream().mapToInt(Integer::intValue).sum();

        DecimalFormat df = new DecimalFormat("###,###");

        // 결과 조립
        for (Map<String, Object> item : usageList) {
            // 1. 계산에 필요한 값들을 먼저 안전하게 숫자로 변환 (Raw Data)
            int apiCallLimit = (int) Double.parseDouble(String.valueOf(item.get("api_call_limit")));
            int basePrice = (int) Double.parseDouble(String.valueOf(item.get("price")));
            int extraUnitPrice = (int) Double.parseDouble(String.valueOf(item.get("extra_api_unit_price")));

            // 2. 비즈니스 로직 계산 (순수 숫자 연산)
            int overApiCnt = Math.max(0, totalRealTimeCnt - apiCallLimit);
            int overApiAmt = overApiCnt * extraUnitPrice;
            int totalBill = basePrice + (overApiAmt);

            // 3. 결과 조립 (화면 표시용 데이터는 새로운 키에 담거나 포맷팅)
            item.put("totalRealTimeCnt", totalRealTimeCnt + "건");
            item.put("api_call_limit", apiCallLimit + "건"); // 기존 값을 덮어쓰기
            item.put("over_api_cnt", overApiCnt + "건");
            item.put("over_api_amt", overApiAmt);

            // 천 단위 콤마 추가 (예: 264,000원)
            item.put("bill", df.format(totalBill) + "원");

            // 계산된 순수 숫자값도 필요할 수 있으니 남겨둠 (선택사항)
            item.put("bill_raw", totalBill);
        }


        return AjaxResult.success(null, usageList);
    }
}
