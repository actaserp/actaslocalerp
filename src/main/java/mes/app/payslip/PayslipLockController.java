package mes.app.payslip;

import lombok.extern.slf4j.Slf4j;
import mes.app.payslip.Service.PayslipLockService;
import mes.app.payslip.config.PayslipLockInterceptor;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 급여명세서 잠금 화면 · 해제 · 비밀번호 변경.
 *
 * 해제는 세션 단위이고, 미사용 시간이 지나면 인터셉터가 자동으로 다시 잠근다.
 * 요청마다 묻지 않는 이유는, 그러면 담당자가 지쳐서 비밀번호를
 * 모니터에 붙여두게 되기 때문이다.
 */
@Slf4j
@Controller
public class PayslipLockController {

	/** 실패 횟수. 세션 단위로만 센다. */
	private static final String FAIL_COUNT = "payslipLockFail";
	private static final int MAX_FAIL = 5;

	/** 새 비밀번호 최소 길이. 초기값 0000 을 고려해 4 로 둔다. */
	private static final int MIN_LENGTH = 4;

	@Autowired
	PayslipLockService lockService;

	// ── 잠금 화면 ───────────────────────────────────────────

	/**
	 * @param returnUrl 423 을 받은 화면이 알려준 자기 주소.
	 *                  화면 요청이 막힌 경우는 인터셉터가 세션에 이미 넣어둔다.
	 */
	@GetMapping("/payslip/lock")
	public ModelAndView lock(HttpSession session,
													 @RequestParam(value = "returnUrl", required = false) String returnUrl) {

		// 이미 풀린 상태로 들어오면 돌아갈 곳으로 보낸다
		if (Boolean.TRUE.equals(session.getAttribute(PayslipLockInterceptor.UNLOCKED))) {
			return new ModelAndView("redirect:" + backUrl(session));
		}
		if (safePath(returnUrl)) {
			session.setAttribute(PayslipLockInterceptor.RETURN_URL, returnUrl);
		}
		return new ModelAndView("payroll/payslip_lock");
	}

	@PostMapping("/payslip/unlock")
	@ResponseBody
	public AjaxResult unlock(@RequestBody Map<String, String> body, HttpSession session) {

		AjaxResult r = new AjaxResult();
		r.success = false;

		int fail = failCount(session);
		if (fail >= MAX_FAIL) {
			r.message = "입력 횟수를 초과했습니다. 로그아웃 후 다시 시도하세요.";
			return r;
		}

		try {
			if (lockService.matches(body.get("password"))) {
				session.setAttribute(PayslipLockInterceptor.UNLOCKED, true);
				session.setAttribute(PayslipLockInterceptor.LAST_USED, System.currentTimeMillis());
				session.removeAttribute(FAIL_COUNT);

				// 해제 직후 화면 진입 한 번만 통과시키는 표시.
				// always-ask 일 때 돌아온 화면이 곧바로 다시 잠기는 것을 막는다.
				session.setAttribute(PayslipLockInterceptor.FRESH, true);

				// 원래 보던 탭 주소로 돌려보낸다. 한 번 쓰고 지운다.
				String back = backUrl(session);
				session.removeAttribute(PayslipLockInterceptor.RETURN_URL);

				Map<String, Object> d = new LinkedHashMap<>();
				d.put("returnUrl", back);
				r.data = d;
				r.success = true;
				return r;
			}
		} catch (Exception e) {
			log.error("[Payslip] 잠금 비밀번호 확인 실패", e);
			r.message = "잠금 설정을 읽지 못했습니다. 관리자에게 문의하세요.";
			return r;
		}

		session.setAttribute(FAIL_COUNT, fail + 1);
		log.warn("[Payslip] 잠금 해제 실패 {}회", fail + 1);
		r.message = "비밀번호가 올바르지 않습니다. (" + (fail + 1) + "/" + MAX_FAIL + ")";
		return r;
	}

	/** 자리를 비울 때 담당자가 직접 다시 잠근다. */
	@PostMapping("/payslip/relock")
	@ResponseBody
	public AjaxResult relock(HttpSession session) {
		session.removeAttribute(PayslipLockInterceptor.UNLOCKED);
		session.removeAttribute(PayslipLockInterceptor.LAST_USED);
		AjaxResult r = new AjaxResult();
		r.success = true;
		return r;
	}

	// ── 비밀번호 변경 ───────────────────────────────────────

	/** 설정 팝업을 열 때 필요한 정보. 잠금이 풀린 상태에서만 호출된다. */
	@GetMapping("/payslip/lockInfo")
	@ResponseBody
	public AjaxResult lockInfo(Authentication auth) {
		AjaxResult r = new AjaxResult();
		Map<String, Object> d = new LinkedHashMap<>();

		boolean admin = lockService.isAdmin(username(auth));
		d.put("admin", admin);
		// 관리자는 현재 비밀번호 없이 바꿀 수 있다. 담당자 퇴사·분실 시의 복구 경로다.
		d.put("needCurrent", !admin);
		d.put("initial", lockService.isInitial());
		d.put("last", lockService.lastChange());

		r.data = d;
		r.success = true;
		return r;
	}

	@PostMapping("/payslip/changePassword")
	@ResponseBody
	public AjaxResult changePassword(@RequestBody Map<String, String> body, Authentication auth) {

		AjaxResult r = new AjaxResult();
		r.success = false;

		String username = username(auth);
		String current  = body.get("current");
		String next     = body.get("next");
		String confirm  = body.get("confirm");

		if (!StringUtils.hasText(next) || next.trim().length() < MIN_LENGTH) {
			r.message = "새 비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.";
			return r;
		}
		if (!next.equals(confirm)) {
			r.message = "새 비밀번호가 서로 다릅니다.";
			return r;
		}

		try {
			// 관리자가 아니면 현재 비밀번호를 반드시 확인한다.
			// 잠금이 풀린 화면을 그대로 두고 자리를 뜬 사이 남이 바꾸는 것을 막는다.
			if (!lockService.isAdmin(username) && !lockService.matches(current)) {
				r.message = "현재 비밀번호가 올바르지 않습니다.";
				return r;
			}
			if (lockService.matches(next)) {
				r.message = "현재와 같은 비밀번호입니다.";
				return r;
			}

			lockService.change(next.trim(), username);
			r.success = true;
			r.message = "비밀번호가 변경되었습니다.";

		} catch (Exception e) {
			log.error("[Payslip] 잠금 비밀번호 변경 오류", e);
			r.message = "변경 중 오류가 발생했습니다.";
		}
		return r;
	}

	// ── 내부 ────────────────────────────────────────────────

	/** 돌아갈 주소. 없으면 급여 화면으로 보낸다. */
	private String backUrl(HttpSession session) {
		Object v = session.getAttribute(PayslipLockInterceptor.RETURN_URL);
		String url = v == null ? null : String.valueOf(v);
		return safePath(url) ? url : "/payslip/payslip";
	}

	/**
	 * 돌아갈 주소가 이 사이트 안인지 확인한다.
	 *
	 * 검사 없이 받으면 외부 주소를 넣어 사용자를 다른 사이트로 보낼 수 있다.
	 * '//' 로 시작하면 브라우저가 프로토콜 상대 주소로 읽어 외부로 나간다.
	 */
	private boolean safePath(String url) {
		return StringUtils.hasText(url) && url.startsWith("/") && !url.startsWith("//");
	}

	private int failCount(HttpSession session) {
		Object v = session.getAttribute(FAIL_COUNT);
		return v == null ? 0 : (int) v;
	}

	private String username(Authentication auth) {
		try {
			return ((User) auth.getPrincipal()).getUsername();
		} catch (Exception e) {
			return null;
		}
	}
}