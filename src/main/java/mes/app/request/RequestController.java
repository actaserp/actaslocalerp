package mes.app.request;

import lombok.extern.slf4j.Slf4j;
import mes.app.request.service.RequestService;
import mes.domain.entity.TbAs011;
import mes.domain.entity.User;
import mes.domain.entity.commute.TB_PB201;
import mes.domain.model.AjaxResult;
import mes.domain.repository.TbAs010Repository;
import mes.domain.repository.TbAs011Repository;
import mes.domain.repository.TbAs020Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/request")
public class RequestController {

    @Autowired
    RequestService requestService;

    @Autowired
    private TbAs011Repository tbAs011Repository;

    @Autowired
    private TbAs010Repository tbAs010Repository;

    @Autowired
    private TbAs020Repository tbAs020Repository;

    // 사용자 정보 조회(부서 이름 출근여부)
    @GetMapping("/search")
    public AjaxResult searchDatas(
            HttpServletRequest request,
            @RequestParam(value="searchfrdate") String searchfrdate,
            @RequestParam(value="searchtodate") String searchtodate,
            @RequestParam(value="searchCompCd", required=false) Integer searchCompCd,
            @RequestParam(value="reqType", required=false) Integer reqType,
            @RequestParam(value="spjangcd", required=false) String spjangcd,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User)auth.getPrincipal();
        String username = user.getUsername();

//        List<TB_PB201> searchData = tbAs010Repository.find(tbPb201Pk);
        List<Map<String, Object>> searchDatas  = requestService.searchDatas(
                searchfrdate
                , searchtodate
                , searchCompCd
                , reqType
                , spjangcd
        );

        result.data = searchDatas;

        return result;
    }

}
