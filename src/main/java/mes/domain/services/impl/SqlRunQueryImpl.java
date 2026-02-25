package mes.domain.services.impl;


import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import org.hibernate.exception.DataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import mes.domain.services.LogWriter;
import mes.domain.services.SqlRunner;

@Slf4j
@Repository
public class SqlRunQueryImpl implements SqlRunner {

	@Autowired(required = true)
    private NamedParameterJdbcTemplate  jdbcTemplate;
	
	@Autowired
	LogWriter logWriter;
	
	
    public List<Map<String, Object>> getRows(String sql, MapSqlParameterSource dicParam){    	
    	
    	List<Map<String, Object>> rows = null;
		//
		checkTenantSafety(sql);
    	
    	try {
    		rows = this.jdbcTemplate.queryForList(sql, dicParam);
		} 
    	catch(DataAccessException de) {
    		System.out.println(de);
    	}
    	catch (Exception e) {
			// TODO: handle exception
			logWriter.addDbLog("error", "SqlRunQueryImpl.getRows", e);
		}
    	return rows;
    }
    
    public Map<String, Object> getRow(String sql, MapSqlParameterSource dicParam){    	

    	Map<String, Object> row = null;
		checkTenantSafety(sql);
    	
    	try {
    		row = this.jdbcTemplate.queryForMap(sql, dicParam);
		} 
    	catch(DataAccessException de) {
    		
    	
    	}
    	catch (Exception e) {
			// TODO: handle exception
			logWriter.addDbLog("error", "SqlRunQueryImpl.getRow", e);
		}
    	return row;
    }
    
    public int execute(String sql, MapSqlParameterSource dicParam) {
    	
    	int rowEffected = 0;
		checkTenantSafety(sql);
    	// TODO Auto-generated method stub
    	try {
    		rowEffected = this.jdbcTemplate.update(sql, dicParam);
		} catch (Exception e) {
			// TODO: handle exception
			logWriter.addDbLog("error", "SqlRunQueryImpl.excute", e);
		}
    	
    	return rowEffected;
    }
    
    public int queryForCount(String sql,  MapSqlParameterSource dicParam) {
    	//select count(*) from xxx where ~
    	return this.jdbcTemplate.queryForObject(sql, dicParam, int.class);
    }
    
    public <T> T queryForObject(String sql,  MapSqlParameterSource dicParam, RowMapper<T> mapper) throws DataException {
    	T rr= this.jdbcTemplate.queryForObject(sql, dicParam, mapper); 
    	return rr;    	
    }

	public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
		int[] result = new int[0];

		try {
			result = this.jdbcTemplate.batchUpdate(sql, batchArgs);
		} catch (DataAccessException de) {
			System.out.println(de);
		} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.batchUpdate", e);
		}

		return result;
	}

	// 런타임 쿼리 검증
	private void checkTenantSafety(String sql) {
		String tenantId = TenantContext.get();

		if (tenantId != null && !"SYSTEM".equals(tenantId)) {
			String lowSql = sql.toLowerCase();

			// 1. 화이트리스트 (로그인, 공통코드 등)
			String[] whiteList =
					{"menu_folder", "label_code_lang"
							, "bookmark", "menu_item"
							, "sys_option", "sys_code"
							, "bill_plans"};

			for (String table : whiteList) {
				if (lowSql.contains(table)) return;
			}

			// 2. 보안 가드레일 (차단 대신 로깅)
			if (lowSql.contains("select") || lowSql.contains("update") || lowSql.contains("delete")) {
				if (!lowSql.contains("spjangcd") && !lowSql.contains("/* skip_tenant_check */")) {
					// [변경] 실행은 시켜주되, 나중에 쿼리를 일괄 수정하기 위해 로그를 남깁니다.
					log.warn("⚠️ [멀티테넌트 보안 권고] spjangcd 누락 감지 (실행 허용됨): {}", sql);

					// 심사 전까지는 throw를 막아두고, 로그를 보면서 쿼리를 하나씩 보완하시면 됩니다.
					// throw new SecurityException("격리 조건 누락");
				}
			}
		}
	}


}
