package mes.app.payslip.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 급여명세서 잠금 인터셉터 등록.
 *
 * 화면 진입 주소(payslip.lock.screen-paths)도 대상에 넣는다.
 * 이게 없으면 탭이 여는 /gui/{메뉴코드}/default 가 인터셉터를 타지 않아
 * 화면은 그냥 뜨고 첫 API 호출에서야 잠금이 걸린다.
 *
 * 잠금 화면과 해제 요청은 제외한다. 빼먹으면 잠금 화면으로 가려다
 * 다시 잠금 화면으로 보내는 무한 리다이렉트가 된다.
 *
 * 비밀번호 변경(/payslip/changePassword)은 제외하지 않는다.
 * 잠금이 풀린 사람만 바꿀 수 있어야 하기 때문이다.
 */
@Configuration
public class PayslipLockConfig implements WebMvcConfigurer {

	@Autowired
	PayslipLockInterceptor payslipLockInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {

		List<String> patterns = new ArrayList<>();
		patterns.add("/payslip/**");
		patterns.add("/api/payslip/**");
		patterns.addAll(payslipLockInterceptor.screenList());

		registry.addInterceptor(payslipLockInterceptor)
			.addPathPatterns(patterns.toArray(new String[0]))
			.excludePathPatterns("/payslip/lock", "/payslip/unlock");
	}
}