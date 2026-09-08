package mes.app.payslip.Service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 급여명세서 전용 SQL 실행기. 항상 외부 MSSQL(ERP_ACTAS)로만 나간다.
 *
 * DataSourceConfig 의 payslipDataSource 빈을 생성자로 직접 받는다.
 *
 * ── 설계 의도 세 가지 ──────────────────────────────────
 *
 * 1. SqlRunner 인터페이스를 구현하지 않는다.
 *    구현하면 이 프로젝트의 SqlRunner 인터페이스가 바뀔 때마다 같이 깨지고,
 *    컴포넌트 스캔에서 기존 빈과 후보가 겹쳐 다른 화면의 주입까지 흔들린다.
 *    PayslipService 가 실제로 쓰는 세 메서드만 둔다.
 *
 * 2. NamedParameterJdbcTemplate 을 @Bean 으로 만들지 않는다.
 *    이 프로젝트는 namedParameterJdbcTemplate 에 @Primary 를 붙여
 *    테넌트 검증이 걸린 jdbcTemplate 을 쓰게 해 두었다.
 *    여기서 같은 타입의 빈을 또 만들면 그 구성이 흔들린다.
 *
 * 3. getRows · execute 는 예외를 삼키지 않는다.
 *    MainSqlRunnerImpl 처럼 오류를 로그만 남기고 null · 0 을 돌려주면,
 *    메일이 안 나갔는데 성공으로 이력에 남는 사고가 난다.
 *    예외가 올라가야 PayslipController 의 catch 가 실패로 기록한다.
 */
@Component
public class PayslipSqlRunner {

	private final NamedParameterJdbcTemplate jdbc;

	public PayslipSqlRunner(@Qualifier("payslipDataSource") DataSource payslipDataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(payslipDataSource);
	}

	public List<Map<String, Object>> getRows(String sql, MapSqlParameterSource p) {
		return jdbc.queryForList(sql, param(p));
	}

	/**
	 * 결과가 없으면 null.
	 *
	 * PayslipService.getPayslip 이 head 의 null · isEmpty 로
	 * "급여 자료가 없습니다" 를 판단한다. 예외를 그대로 올리면 500 이 난다.
	 */
	public Map<String, Object> getRow(String sql, MapSqlParameterSource p) {
		try {
			return jdbc.queryForMap(sql, param(p));
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	public int execute(String sql, MapSqlParameterSource p) {
		return jdbc.update(sql, param(p));
	}

	private MapSqlParameterSource param(MapSqlParameterSource p) {
		return p != null ? p : new MapSqlParameterSource();
	}
}