package mes.app.request;

import lombok.extern.slf4j.Slf4j;
import mes.app.request.service.RequestCurrentService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/request_current")
public class RequestCurrentController {
    @Autowired
    RequestCurrentService requestCurrentService;

    // 요청사항 조회 (처리 대기 목록)
    @GetMapping("/search")
    public AjaxResult searchDatas(
            HttpServletRequest request,
            @RequestParam(value="searchfrdate") String searchfrdate,
            @RequestParam(value="searchtodate") String searchtodate,
            @RequestParam(value="searchCompCd", required=false) Integer searchCompCd,
            @RequestParam(value="reqType", required=false) String reqType,
            @RequestParam(value="spjangcd", required=false) String spjangcd,
            Authentication auth) {
        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> searchDatas = requestCurrentService.searchDatas(
                searchfrdate
                , searchtodate
                , searchCompCd
                , reqType
                , spjangcd
        );

        result.data = searchDatas;

        return result;
    }

    // 상세정보 조회
    @GetMapping("/detail")
    public AjaxResult getRequestDetail(
            @RequestParam("asid") Integer asid,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        
        Map<String, Object> item = requestCurrentService.getDetail(asid);
        result.data = item;
        
        return result;
    }

    // 처리내용 저장
    @PostMapping("/save")
    @Transactional
    public AjaxResult saveProcess(@RequestBody Map<String, Object> payload, Authentication auth) {
        User user = (User) auth.getPrincipal();
        AjaxResult result = new AjaxResult();

        try {
            result = requestCurrentService.saveProcess(payload, user);
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = e.getMessage();
        }

        return result;
    }
}
