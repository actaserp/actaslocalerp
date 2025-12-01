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
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.sql.Timestamp;
import java.util.*;

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

    // 거래처 정보 조회
    @GetMapping("/searchUser")
    public AjaxResult getUserInfo(
            HttpServletRequest request,
            @RequestParam(value="compid") String compid,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User)auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> searchData  = requestService.searchUserInfo( compid );

        result.data = searchData;

        return result;
    }

    // 요청사항 조회
    @GetMapping("/search")
    public AjaxResult searchDatas(
            HttpServletRequest request,
            @RequestParam(value="searchfrdate") String searchfrdate,
            @RequestParam(value="searchtodate") String searchtodate,
            @RequestParam(value="searchCompCd", required=false) String searchCompCd,
            @RequestParam(value="reqType", required=false) String reqType,
            @RequestParam(value="spjangcd", required=false) String spjangcd,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User)auth.getPrincipal();
        String username = user.getUsername();

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

    // 상세정보 조회
    @GetMapping("/detail")
    public AjaxResult getRequestDetail(
            @RequestParam("id") Integer id,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        
        Map<String, Object> item = requestService.getDetail(id);
        result.data = item;
        
        return result;
    }

    // 저장
    @PostMapping("/save")
    @Transactional
    public AjaxResult saveRequest(@RequestBody Map<String, Object> payload, Authentication auth) {
        User user = (User) auth.getPrincipal();
        AjaxResult result = new AjaxResult();

        try {
            result = requestService.saveRequest(payload, user);
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = e.getMessage();
        }

        return result;
    }

    @PostMapping("/uploadFile")
    public AjaxResult uploadFile(@RequestParam("uploadFile") MultipartFile file) {
        AjaxResult result = new AjaxResult();
        try {
            if (file.isEmpty()) {
                result.success = false;
                result.message = "파일이 비어 있습니다.";
                return result;
            }

            String uuid = UUID.randomUUID().toString();
            String ext = FilenameUtils.getExtension(file.getOriginalFilename());
            if (!"pdf".equalsIgnoreCase(ext)) {
                result.success = false;
                result.message = "PDF 파일만 업로드 가능합니다.";
                return result;
            }

            String newFileName = uuid + ".pdf";
            File dir = new File("C:\\temp\\as_request\\files");
            if (!dir.exists()) dir.mkdirs();

            File dest = new File(dir, newFileName);
            file.transferTo(dest);

            result.success = true;
            result.data = newFileName; // 프론트에 반환
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = "파일 업로드 실패: " + e.getMessage();
        }
        return result;
    }


}
