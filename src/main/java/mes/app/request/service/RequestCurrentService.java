package mes.app.request.service;

import mes.domain.entity.TbAs010;
import mes.domain.entity.TbAs011;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.TbAs010Repository;
import mes.domain.repository.TbAs011Repository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
public class RequestCurrentService {
    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    TbAs011Repository tbAs011Repository;

    @Autowired
    TbAs010Repository tbAs010Repository;

    // 요청사항 조회 (처리 대기 목록)
    public List<Map<String, Object>> searchDatas(
            String searchfrdate
            , String searchtodate
            , Integer searchCompCd
            , String reqType
            , String spjangcd
    ) {
        // 날짜 형식 변환 (YYYY-MM-DD -> YYYYMMDD)
        if (searchfrdate != null && searchfrdate.contains("-")) {
            searchfrdate = searchfrdate.replaceAll("-", "");
        }
        if (searchtodate != null && searchtodate.contains("-")) {
            searchtodate = searchtodate.replaceAll("-", "");
        }

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("searchfrdate", searchfrdate);
        dicParam.addValue("searchtodate", searchtodate);
        dicParam.addValue("searchCompCd", searchCompCd);
        dicParam.addValue("reqType", reqType);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    a."asid" AS id,
                    TO_CHAR(TO_DATE(a."asdate", 'YYYYMMDD'), 'YYYY-MM-DD') AS asdate,
                    a."cltnm",
                    a."cltcd",
                    a."userid",
                    a."usernm",
                    a."asperid",
                    a."aspernm",
                    a."retitle",
                    a."remark" AS content,
                    a."asdv",
                    sc1."Value" AS asdv_nm,
                    a."asmenu",
                    a."recyn",
                    sc2."Value" AS recyn_nm,
                    a."recperid",
                    a."recpernm",
                    a."recdate",
                    a."endperid",
                    a."endpernm",
                    TO_CHAR(TO_DATE(a."enddate", 'YYYYMMDD'), 'YYYY-MM-DD') AS enddate,
                    TO_CHAR(a."inputdate", 'YYYY-MM-DD HH24:MI') AS inputdate,
                    CASE WHEN f."fixid" IS NOT NULL THEN 'Y' ELSE 'N' END AS hasProcess
                FROM "tb_as010" a
                LEFT JOIN "sys_code" sc1
                    ON sc1."Code" = a."asdv"
                   AND sc1."CodeType" = 'sale_type'
                LEFT JOIN "sys_code" sc2
                    ON sc2."Code" = a."recyn"
                   AND sc2."CodeType" = 'recyn'
                LEFT JOIN "tb_as011" f
                    ON f."asid" = a."asid"
                WHERE 1=1
        		""";

        // 날짜 조건 추가
        if (searchfrdate != null && !searchfrdate.isEmpty()) {
            sql += " AND a.\"asdate\" >= :searchfrdate ";
        }
        if (searchtodate != null && !searchtodate.isEmpty()) {
            sql += " AND a.\"asdate\" <= :searchtodate ";
        }

        // 업체 조건 추가
        if (searchCompCd != null) {
            sql += " AND a.\"cltnm\" IN (SELECT \"Name\" FROM company WHERE id = :searchCompCd) ";
        }

        // 요청구분 조건 추가
        if (reqType != null && !reqType.isEmpty()) {
            sql += " AND a.\"asdv\" = :reqType ";
        }

        sql += " ORDER BY a.\"asdate\" DESC, a.\"inputdate\" DESC ";

        List<Map<String, Object>> item = this.sqlRunner.getRows(sql, dicParam);

        return item;
    }

    // 상세정보 조회 (요청사항 + 처리내용)
    public Map<String, Object> getDetail(Integer asid) {
        MapSqlParameterSource paramMap = new MapSqlParameterSource();
        paramMap.addValue("asid", asid);

        // 요청사항 조회
        String sql = """
            SELECT
                a."asid" AS id,
                TO_CHAR(TO_DATE(a."asdate", 'YYYYMMDD'), 'YYYY-MM-DD') AS asdate,
                a."cltnm",
                a."cltcd",
                a."userid",
                a."usernm",
                a."asperid",
                a."aspernm",
                a."retitle" AS title,
                a."remark" AS content,
                a."asdv" AS reqType,
                sc1."Value" AS reqType_nm,
                a."asmenu" AS scrNum,
                a."recyn",
                sc2."Value" AS recyn_nm,
                a."recperid",
                a."recpernm",
                a."recdate",
                a."endperid",
                a."endpernm",
                TO_CHAR(TO_DATE(a."enddate", 'YYYYMMDD'), 'YYYY-MM-DD') AS endDate,
                TO_CHAR(a."inputdate", 'YYYY-MM-DD HH24:MI') AS inputdate
            FROM "tb_as010" a
            LEFT JOIN "sys_code" sc1
                ON sc1."Code" = a."asdv"
               AND sc1."CodeType" = 'sale_type'
            LEFT JOIN "sys_code" sc2
                ON sc2."Code" = a."recyn"
               AND sc2."CodeType" = 'recyn'
            WHERE a."asid" = :asid
            """;

        Map<String, Object> item = this.sqlRunner.getRow(sql, paramMap);

        // 처리내용 조회
        if (item != null) {
            String fixSql = """
                SELECT
                    f."fixid",
                    TO_CHAR(TO_DATE(f."fixdate", 'YYYYMMDD'), 'YYYY-MM-DD') AS fixdate,
                    f."asperid",
                    f."aspernm",
                    f."remark" AS processContent,
                    TO_CHAR(f."inputdate", 'YYYY-MM-DD HH24:MI') AS inputdate
                FROM "tb_as011" f
                WHERE f."asid" = :asid
                ORDER BY f."inputdate" DESC
                LIMIT 1
                """;
            Map<String, Object> fixData = this.sqlRunner.getRow(fixSql, paramMap);
            if (fixData != null) {
                item.put("fixid", fixData.get("fixid"));
                item.put("fixdate", fixData.get("fixdate"));
                item.put("processContent", fixData.get("processContent"));
            }
        }

        return item;
    }

    // 처리내용 저장
    @Transactional
    public AjaxResult saveProcess(Map<String, Object> payload, User user) {
        AjaxResult result = new AjaxResult();

        try {
            // asid 필수 체크
            Integer asid = payload.get("asid") != null && !payload.get("asid").toString().isEmpty()
                    ? Integer.parseInt(payload.get("asid").toString())
                    : null;

            if (asid == null) {
                result.success = false;
                result.message = "요청사항 ID가 필요합니다.";
                return result;
            }

            // 요청사항 존재 확인
            TbAs010 request = tbAs010Repository.findById(asid)
                    .orElseThrow(() -> new RuntimeException("요청사항을 찾을 수 없습니다."));

            // fixid 체크 (수정 모드)
            Integer fixid = payload.get("fixid") != null && !payload.get("fixid").toString().isEmpty()
                    ? Integer.parseInt(payload.get("fixid").toString())
                    : null;

            TbAs011 entity = null;

            // 수정 모드
            if (fixid != null) {
                entity = tbAs011Repository.findById(fixid)
                        .orElseThrow(() -> new RuntimeException("처리내용을 찾을 수 없습니다."));
            }
            // 신규 등록 모드
            else {
                entity = new TbAs011();
                entity.setAsid(asid);
                entity.setInputdate(new Timestamp(System.currentTimeMillis()));
            }

            // 처리일자
            String fixdate = cleanDate(payload.get("fixdate") != null ? payload.get("fixdate") : payload.get("rptdate"));
            if (fixdate != null) {
                entity.setFixdate(fixdate);
            }

            // 처리자 정보
            entity.setAsperid(String.valueOf(user.getId()));
            entity.setAspernm(user.getUsername());

            // 처리내용 (Toast UI Editor HTML)
            if (payload.get("remark") != null) {
                entity.setRemark((String) payload.get("remark"));
            } else if (payload.get("processContent") != null) {
                entity.setRemark((String) payload.get("processContent"));
            }

            // 수정일자 업데이트
            entity.setInputdate(new Timestamp(System.currentTimeMillis()));

            tbAs011Repository.save(entity);

            // 요청사항의 진행구분 업데이트 (처리 완료로 변경)
            if (payload.get("recyn") != null) {
                request.setRecyn(payload.get("recyn").toString());
                request.setEndperid(String.valueOf(user.getId()));
                request.setEndpernm(user.getUsername());
                String enddate = cleanDate(payload.get("fixdate") != null ? payload.get("fixdate") : payload.get("rptdate"));
                if (enddate != null) {
                    request.setEnddate(enddate);
                }
                tbAs010Repository.save(request);
            }

            result.success = true;
            result.data = entity.getFixid();

        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = e.getMessage();
        }

        return result;
    }

    private String cleanDate(Object v) {
        if (v == null) return null;
        String dateStr = v.toString();
        return dateStr.replaceAll("-", "");
    }

}
