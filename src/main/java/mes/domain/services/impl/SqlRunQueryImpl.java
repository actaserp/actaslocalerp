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
//		checkTenantSafety(sql);
    	
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
//		checkTenantSafety(sql);
    	
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
//		checkTenantSafety(sql);
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

			// 1. 예외 테이블 리스트 (공통 코드, 메뉴 등)
			// 여기에 추가만 하면 이 테이블이 포함된 쿼리는 통과됩니다.
			String[] whiteList = {"menu_folder", "label_code_lang", "bookmark", "menu_item"};

			for (String table : whiteList) {
				if (lowSql.contains(table)) return; // 화이트리스트 테이블이 있으면 검사 패스
			}

			// 2. 기존 로직 (SELECT, UPDATE, DELETE 검사)
			if (lowSql.contains("select") || lowSql.contains("update") || lowSql.contains("delete")) {
				if (!lowSql.contains("spjangcd") && !lowSql.contains("/* skip_tenant_check */")) {
					log.error(" [보안 위반] 사업장 격리 조건 누락 쿼리 차단: {}", sql);
					throw new SecurityException("데이터 격리 정책 위반: 'spjangcd' 조건이 누락되었습니다.");
				}
			}
		}
	}


}
