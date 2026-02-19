package mes.app.system.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RealTimeUsageService {

    @Autowired
    SqlRunner sqlRunner;

    public List<Map<String, Object>> getUsageList(String spjangcd){

        MapSqlParameterSource param = new MapSqlParameterSource();

        param.addValue("spjangcd", spjangcd);

        String sql = """
                select b.name
                ,b.price  --단가
                ,b.api_call_limit as api_call_limit --기본제공 api
                ,b.remark --비고
                ,b.extra_api_unit_price
                from tb_xa012 a
                left join bill_plans b on a.bill_plans_id = b.id
                where
                spjangcd = :spjangcd
                """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

        return items;
    }
}
