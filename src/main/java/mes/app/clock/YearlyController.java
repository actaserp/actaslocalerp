package mes.app.clock;

import lombok.extern.slf4j.Slf4j;
import mes.app.clock.service.ClockYearlyService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

/**
 * 연차관리 (회계연도 기준).
 *
 * <pre>
 *   GET  /read        현황 목록
 *   GET  /detail      사원 1명의 발생·사용 내역
 *   GET  /Create      연차·월차 생성 미리계산 (저장하지 않는다)
 *   POST /save        생성 결과 저장
 *   POST /saveAdjust  조정일수 저장
 * </pre>
 *
 * 예전에는 「연차생성」·「월차생성」이 각각 조회 후 곧바로 저장까지 해버려서
 * 사용자가 결과를 보기 전에 데이터가 바뀌었다. 지금은 {@code /Create} 가 계산만 하고
 * 화면에서 확인한 뒤 {@code /save} 로 저장한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/clock/Yearly")
public class YearlyController {

    @Autowired
    private ClockYearlyService clockYearlyService;

    @GetMapping("/read")
    public AjaxResult getYearlyList(
            @RequestParam(value = "year") String year,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "rtflag", required = false) String rtflag,
            HttpServletRequest request) {

        List<Map<String, Object>> items = this.clockYearlyService.getYearlyList(year, name, spjangcd, rtflag);
        for (Map<String, Object> item : items) {
            item.put("rtdate", hyphen(item.get("rtdate")));
        }

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    /**
     * 연차·월차 생성 미리계산. 저장하지 않는다.
     *
     * @param startdate  기준일자 (yyyy-MM). 월차 개근월수를 셀 끝 지점
     * @param createFlag '1' 기존 생성분까지 다시 계산 / '2' 신규만 (기본값)
     */
    @GetMapping("/Create")
    public AjaxResult create(
            @RequestParam(value = "year") String year,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "startdate", required = false) String startdate,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "rtflag", required = false) String rtflag,
            @RequestParam(value = "createFlag", required = false, defaultValue = "2") String createFlag,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items =
                this.clockYearlyService.createAll(year, spjangcd, startdate, name, rtflag, createFlag);

        for (Map<String, Object> item : items) {
            item.put("rtdate", hyphen(item.get("rtdate")));
        }
        result.data = items;
        return result;
    }

    /** 생성 결과 저장 */
    @PostMapping("/save")
    @Transactional
    public AjaxResult save(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> saveList = (List<Map<String, Object>>) requestData.get("saveList");
        String spjangcd = String.valueOf(requestData.get("spjangcd"));
        String year = String.valueOf(requestData.get("year"));
        Object flag = requestData.get("createFlag");
        String createFlag = (flag == null) ? "2" : String.valueOf(flag);

        int saved = this.clockYearlyService.saveGenerated(saveList, year, spjangcd, createFlag);

        result.success = true;
        result.data = Map.of("saved", saved);
        result.message = saved + "건 저장되었습니다.";
        return result;
    }

    /**
     * 조정일수 저장.
     *
     * 발생행이 없는 사원은 저장되지 않고 이름이 돌아온다 — 연차생성이 먼저다.
     */
    @PostMapping("/saveAdjust")
    @Transactional
    public AjaxResult saveAdjust(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> saveList = (List<Map<String, Object>>) requestData.get("saveList");
        String spjangcd = String.valueOf(requestData.get("spjangcd"));
        String year = String.valueOf(requestData.get("year"));

        Map<String, Object> out =
                this.clockYearlyService.saveAdjust(saveList, year, spjangcd, actorName(auth));

        result.success = true;
        result.data = out;
        return result;
    }

    @GetMapping("/detail")
    public AjaxResult getYearlyDetail(
            @RequestParam(value = "id") Integer id,
            @RequestParam(value = "year") String year,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> items = this.clockYearlyService.getYearlyDetail(id, year, spjangcd);
        for (Map<String, Object> detail : items) {
            detail.put("reqdate", hyphen(detail.get("reqdate")));
            detail.put("frdate", hyphen(detail.get("frdate")));
            detail.put("todate", hyphen(detail.get("todate")));
        }
        result.data = items;
        return result;
    }

    // ------------------------------------------------------------ 구버전 호환

    /**
     * @deprecated 「연차생성」·「월차생성」이 하나로 합쳐졌다. {@link #create} 를 쓴다.
     *             외부에서 직접 호출하던 곳이 있을 수 있어 남겨 두고 같은 동작에 위임한다.
     */
    @Deprecated
    @GetMapping("/YearlyCreat")
    public AjaxResult getYearlyCreat(
            @RequestParam(value = "year") String year,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "startdate", required = false) String startdate,
            @RequestParam(value = "name", required = false) String name,
            HttpServletRequest request) {
        return create(year, spjangcd, startdate, name, null, "2", request);
    }

    /**
     * @deprecated {@link #create} 로 통합. 월차도 같은 호출에서 함께 계산된다.
     */
    @Deprecated
    @GetMapping("/MonthlyCreate")
    public AjaxResult getMonthlyCreate(
            @RequestParam(value = "year") String year,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "startdate", required = false) String startdate,
            @RequestParam(value = "name", required = false) String name,
            HttpServletRequest request) {
        return create(year, spjangcd, startdate, name, null, "2", request);
    }

    /**
     * @deprecated {@link #save} 로 통합.
     */
    @Deprecated
    @PostMapping("/Yearlysave")
    @Transactional
    public AjaxResult saveYearlysave(@RequestBody Map<String, Object> requestData,
                                     HttpServletRequest request, Authentication auth) {
        return save(requestData, request, auth);
    }

    /**
     * @deprecated {@link #save} 로 통합.
     */
    @Deprecated
    @PostMapping("/Monthlysave")
    @Transactional
    public AjaxResult saveMonthlysave(@RequestBody Map<String, Object> requestData,
                                      HttpServletRequest request, Authentication auth) {
        return save(requestData, request, auth);
    }

    // ---------------------------------------------------------------- 내부

    /** yyyyMMdd → yyyy-MM-dd. 8자리가 아니면 그대로 둔다 */
    private static Object hyphen(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        if (s.length() != 8) return v;
        return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
    }

    /** 조정 이력에 남길 작업자 표시명 */
    private static String actorName(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User user)) return "-";
        String name = user.getFirst_name();
        if (name != null && !name.isBlank()) return name.trim();
        return (user.getUsername() == null || user.getUsername().isBlank()) ? "-" : user.getUsername().trim();
    }
}
