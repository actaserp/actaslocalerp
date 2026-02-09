package mes.app.system.service;

import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class CustomerCurrentService {

    @Autowired
    SqlRunner sqlRunner;
    //고객사현황 그리드
    public List<Map<String, Object>> getCustomerList(String srchStartDt, String srchEndDt, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();


        // YYYY-MM-DD → YYYYMMDD
        String st = srchStartDt.replace("-", "");
        String en = srchEndDt.replace("-", "");

        param.addValue("st", st);
        param.addValue("en", en);
        param.addValue("keyword" , "%" + keyword + "%");


        String sql = """
                    select * from tb_xa012
                    where "subscriptiondate" between :st and :en
                """;

        if(!keyword.isEmpty())

        {
            sql += """
                and spjangnm like :keyword
                """;
        }

        sql += """
                order by expirationdate desc
                """;


        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

        return items;
    }
}


