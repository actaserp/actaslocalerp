package mes.app.definition.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class bank_codeService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 은행코드 목록 조회
     */
    public List<Map<String, Object>> getBankCodeList(String name) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("name", name);

        String sql = """
            SELECT bankid AS id,
                   "banknm" AS name,
                   "remark" AS remark,
                   "bankpopcd" AS bankpopcd,
                   "banksubcd" AS banksubcd,
                   "bankcd",
                   "subcd",
                   xbs.refbanknm as refbanksubnm,
                   xbss.refbanknm as refbankpopnm
            FROM tb_xbank
            LEFT JOIN tb_xbanksub xbs ON xbs.refcd = banksubcd
            LEFT JOIN tb_xbanksub xbss ON xbss.refcd = bankpopcd
            WHERE useyn = '1'
        """;

        if (StringUtils.isNotEmpty(name)) {
            sql += " AND upper(\"banknm\") LIKE concat('%%', upper(:name), '%%') ";
        }

        sql += " ORDER BY bankid";

        return this.sqlRunner.getRows(sql, param);
    }

    public List<Map<String, Object>> getPopbillList() {
        String sql = """
        SELECT refcd AS code, refbanknm AS name
        FROM tb_xbanksub
        WHERE flag = '0'
        ORDER BY refcd
    """;
        return this.sqlRunner.getRows(sql, new MapSqlParameterSource());
    }

    public List<Map<String, Object>> getParticipantList() {
        String sql = """
        SELECT refcd AS code, refbanknm AS name
        FROM tb_xbanksub
        WHERE flag = '1'
        ORDER BY refcd
    """;
        return this.sqlRunner.getRows(sql, new MapSqlParameterSource());
    }


    /*
      상세 조회 (사용 안 할 수도 있음)
     */
//    public Map<String, Object> getBankCodeDetail(int id) {
//        MapSqlParameterSource param = new MapSqlParameterSource();
//        param.addValue("id", id);
//
//        String sql = """
//            SELECT id,
//                   "Name" AS name,
//                   "Description" AS description,
//                   "PopbillAgencyCode" AS popbill_agency_code,
//                   "EvalAgencyCode" AS eval_agency_code
//            FROM bank_code
//            WHERE id = :id
//        """;
//
//        return this.sqlRunner.getRow(sql, param);
//    }
}
