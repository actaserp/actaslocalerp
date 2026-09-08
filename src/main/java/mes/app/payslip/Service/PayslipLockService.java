package mes.app.payslip.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 급여명세서 화면 잠금 비밀번호.
 *
 * 저장 위치는 기본 DB(PostgreSQL)의 payslip_lock_config 다.
 * 급여 DB(MSSQL)가 아니다. 잠금은 급여 자료가 아니라 접근 제어 설정이고,
 * 외부망이 끊겨도 잠금 화면은 떠야 하기 때문이다.
 *
 * ── 비밀번호는 해시로만 다룬다 ────────────────────────────
 * 이 비밀번호 하나로 전 직원 급여가 열린다. 평문으로 두면
 * DB 를 조회할 수 있는 사람 · 백업 파일을 받은 사람이 전부 그대로 읽는다.
 * 변경은 화면에서 하므로 담당자가 해시를 만질 일은 없다.
 */
@Slf4j
@Service
public class PayslipLockService {

	/** 테이블이 비어 있을 때 넣는 초기 비밀번호. 첫 진입 후 화면에서 반드시 바꿀 것. */
	private static final String INITIAL_PASSWORD = "0000";

	/**
	 * 현재 비밀번호 없이도 새로 설정할 수 있는 계정.
	 *
	 * 담당자가 퇴사하거나 비밀번호를 잊었을 때의 복구 경로다.
	 * 이 목록을 화면에서 바꿀 수 있게 하면, 잠금을 푼 사람이 자기를
	 * 관리자로 넣고 나갈 수 있어 통제가 무의미해진다. 그래서 설정 파일에만 둔다.
	 */
	@Value("${payslip.lock.admin-users:admin}")
	private String adminUsers;

	private final NamedParameterJdbcTemplate jdbc;
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	/**
	 * 기본 DataSource 를 직접 받아 자체 템플릿을 만든다.
	 *
	 * @Primary 인 namedParameterJdbcTemplate 을 주입받지 않는 이유는,
	 * 그쪽이 DataSourceConfig 의 validateTenantIsolation 을 타기 때문이다.
	 * 이 테이블에는 spjangcd 컬럼이 없어 검증에 걸릴 수 있다.
	 */
	public PayslipLockService(@Qualifier("dataSource") DataSource dataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/** 이 계정이 현재 비밀번호 없이 설정을 바꿀 수 있는가. */
	public boolean isAdmin(String username) {
		if (!StringUtils.hasText(username)) return false;
		List<String> list = Arrays.stream(adminUsers.split(","))
													.map(String::trim)
													.filter(StringUtils::hasText)
													.collect(Collectors.toList());
		return list.contains(username);
	}

	/** 입력한 비밀번호가 맞는가. */
	public boolean matches(String rawPassword) {
		if (!StringUtils.hasText(rawPassword)) return false;
		return encoder.matches(rawPassword, currentHash());
	}

	/**
	 * 비밀번호 변경.
	 *
	 * 갱신이 아니라 새 행을 넣는다. 이전 값이 남지는 않지만
	 * 언제 누가 바꿨는지는 이력으로 남는다.
	 */
	public void change(String newPassword, String username) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("hash", encoder.encode(newPassword));
		p.addValue("who", StringUtils.hasText(username) ? username : "unknown");

		jdbc.update(
			"insert into payslip_lock_config (password_hash, updated_by) values (:hash, :who)", p);

		log.info("[Payslip] 잠금 비밀번호 변경 by {}", username);
	}

	/** 마지막으로 누가 언제 바꿨는지. 설정 화면에 보여준다. */
	public Map<String, Object> lastChange() {
		List<Map<String, Object>> rows = jdbc.queryForList(
			"select updated_by, updated_at from payslip_lock_config order by id desc limit 1",
			new MapSqlParameterSource());
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 아직 초기 비밀번호(0000)를 쓰고 있는가. 화면에서 변경을 권고하는 데 쓴다. */
	public boolean isInitial() {
		return encoder.matches(INITIAL_PASSWORD, currentHash());
	}

	/**
	 * 현재 해시. 테이블이 비어 있으면 초기 비밀번호를 넣고 그 값을 돌려준다.
	 *
	 * 기동 시점이 아니라 첫 접근 때 넣는 이유는, DB 가 잠깐 안 될 때
	 * 애플리케이션 전체가 못 뜨는 상황을 만들지 않기 위해서다.
	 */
	private synchronized String currentHash() {
		List<Map<String, Object>> rows = jdbc.queryForList(
			"select password_hash from payslip_lock_config order by id desc limit 1",
			new MapSqlParameterSource());

		if (!rows.isEmpty()) {
			return String.valueOf(rows.get(0).get("password_hash"));
		}

		String hash = encoder.encode(INITIAL_PASSWORD);
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("hash", hash);
		p.addValue("who", "system");
		jdbc.update(
			"insert into payslip_lock_config (password_hash, updated_by) values (:hash, :who)", p);

		log.warn("[Payslip] 잠금 비밀번호가 없어 초기값({})으로 설정했습니다. 화면에서 즉시 변경하세요.",
			INITIAL_PASSWORD);
		return hash;
	}
}