package mes.app.request.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RequestService {
    @Autowired
    SqlRunner sqlRunner;

    // 사용자 정보 조회
    public List<Map<String, Object>> searchDatas(
            String searchfrdate
            , String searchtodate
            , Integer searchCompCd
            , Integer reqType
            , String spjangcd
    ) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("searchfrdate", searchfrdate);
        dicParam.addValue("searchtodate", searchtodate);
        dicParam.addValue("searchCompCd", searchCompCd);
        dicParam.addValue("reqType", reqType);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                * FROM tb_as010
                  WHERE 1=1
        		""";


        List<Map<String, Object>> item = this.sqlRunner.getRows(sql, dicParam);

        return item;
    }

}
