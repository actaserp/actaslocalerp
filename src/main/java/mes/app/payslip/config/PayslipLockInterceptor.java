package mes.app.payslip.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 급여명세서 화면 잠금.
 *
 * 로그인과 별개로 급여 담당자만 아는 비밀번호를 한 번 더 받는다.
 *
 * ── 화면만 막으면 소용없다 ──────────────────────────────
 * 화면만 막고 /api/payslip/list 를 열어두면 주소창에 API 를 직접 쳐서
 * 전 직원 급여를 그대로 볼 수 있다. 그래서 화면과 API 를 함께 막는다.
 * 화면 요청은 잠금 화면으로 보내고, API 요청은 423 으로 끊는다.
 * 401 은 이 프로젝트의 전역 ajax 처리기가 세션 만료로 해석해 쓰지 않는다.
 *
 * ── 진입할 때마다 다시 묻기 ─────────────────────────────
 * always-ask 가 켜져 있으면 화면에 들어올 때마다 잠금을 풀게 한다.
 * 그런데 그냥 잠그면 해제 직후 돌아온 화면도 다시 잠겨 무한 반복이 된다.
 * 그래서 해제 시점에 FRESH 표시를 남기고, 화면 진입 한 번만 그 표시로 통과시킨다.
 * 표시는 쓰는 즉시 지우므로 그 다음 진입부터는 다시 묻는다.
 */
@Slf4j
@Component
public class PayslipLockInterceptor implements HandlerInterceptor {

	/** 423 Locked. HttpServletResponse 에 상수가 없어 직접 둔다. */
	private static final int HTTP_LOCKED = 423;

	/** 세션에 잠금 해제 표시. 세션이 끝나면 자동으로 다시 잠긴다. */
	public static final String UNLOCKED = "payslipUnlocked";

	/** 마지막 사용 시각. 이걸로 자동 재잠금을 판단한다. */
	public static final String LAST_USED = "payslipLastUsed";

	/** 해제 직후 화면 진입 한 번만 통과시키는 일회용 표시. */
	public static final String FRESH = "payslipFresh";

	/**
	 * 잠금 때문에 막힌 원래 주소. 해제 후 이리로 돌려보낸다.
	 *
	 * 메뉴 탭은 iframe 이고 그 주소는 GuiController 를 거치는 /gui/... 다.
	 * 해제 후 /payslip/payslip 으로 보내면 그 경로를 타지 않아
	 * layout_page 가 요구하는 userinfo 가 비고 렌더링에서 500 이 난다.
	 * 원래 주소로 그대로 돌아가는 것이 가장 안전하다.
	 */
	public static final String RETURN_URL = "payslipReturnUrl";

	/** 개발 중에는 끄고 쓸 수 있게 둔다. 운영에서는 반드시 true. */
	@Value("${payslip.lock.enabled:true}")
	private boolean enabled;

	/** 화면에 들어올 때마다 다시 묻는다. false 면 세션 단위로 한 번만 묻는다. */
	@Value("${payslip.lock.always-ask:true}")
	private boolean alwaysAsk;

	/** 이 시간 동안 요청이 없으면 다시 잠근다. 0 이면 시간 제한 없음. */
	@Value("${payslip.lock.timeout-minutes:30}")
	private long timeoutMinutes;

	/**
	 * 화면 진입 주소. 쉼표로 여러 개.
	 *
	 * 탭이 실제로 여는 주소는 메뉴 테이블에 등록된 /gui/{메뉴코드}/default 다.
	 * 이 값이 실제 주소와 다르면 always-ask 가 동작하지 않는다.
	 * (API 잠금은 그대로 동작하므로 급여 자료가 노출되지는 않는다)
	 */
	@Value("${payslip.lock.screen-paths:/payslip/payslip}")
	private String screenPaths;

	@Override
	public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
		throws Exception {

		if (!enabled) return true;

		String path = req.getRequestURI().substring(req.getContextPath().length());
		boolean isScreen = screenList().contains(path);

		HttpSession session = req.getSession(false);

		if (session != null && Boolean.TRUE.equals(session.getAttribute(UNLOCKED))
					&& !expired(session)) {

			if (!(isScreen && alwaysAsk)) {
				// 쓰고 있는 동안에는 시간이 계속 연장된다
				session.setAttribute(LAST_USED, System.currentTimeMillis());
				return true;
			}

			// 해제 직후 돌아온 첫 진입은 통과시킨다. 안 그러면 무한 반복이 된다.
			if (Boolean.TRUE.equals(session.getAttribute(FRESH))) {
				session.removeAttribute(FRESH);
				session.setAttribute(LAST_USED, System.currentTimeMillis());
				return true;
			}

			// 화면에 다시 들어왔다 — 요구사항에 따라 다시 잠근다
			clear(session);
			log.debug("[Payslip] 화면 재진입 — 다시 잠급니다.");

		} else if (session != null) {
			clear(session);
		}

		if (req.getRequestURI().contains("/api/payslip/")) {
			res.setStatus(HTTP_LOCKED);
			res.setContentType("application/json;charset=UTF-8");
			res.getWriter().write(
				"{\"success\":false,\"locked\":true,"
					+ "\"message\":\"급여명세서 잠금이 해제되지 않았거나 시간이 지났습니다.\"}");
			return false;
		}

		// 탭은 iframe 이라 이 리다이렉트가 탭 안에서 일어난다.
		// 해제 후 이 주소로 그대로 돌아가면 메뉴와 탭이 유지된다.
		// context-path(/mes)는 빼고 저장한다.
		// 붙인 채로 두면 Spring 의 redirect: 가 앞에 한 번 더 붙여 /mes/mes/... 가 된다.
		String qs = req.getQueryString();
		req.getSession(true).setAttribute(RETURN_URL, path + (qs != null ? "?" + qs : ""));

		res.sendRedirect(req.getContextPath() + "/payslip/lock");
		return false;
	}

	/** 등록된 화면 진입 주소 목록. */
	public List<String> screenList() {
		return Arrays.stream(screenPaths.split(","))
						 .map(String::trim)
						 .filter(s -> !s.isEmpty())
						 .collect(Collectors.toList());
	}

	private void clear(HttpSession session) {
		session.removeAttribute(UNLOCKED);
		session.removeAttribute(LAST_USED);
		session.removeAttribute(FRESH);
	}

	private boolean expired(HttpSession session) {
		if (timeoutMinutes <= 0) return false;

		Object last = session.getAttribute(LAST_USED);
		// 시각이 없으면 방금 푼 것으로 본다. 여기서 만료로 처리하면
		// 해제 직후 첫 요청이 바로 튕긴다.
		if (last == null) return false;

		return System.currentTimeMillis() - (long) last >= timeoutMinutes * 60_000L;
	}
}