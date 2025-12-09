package mes.app.account;

import java.io.IOException;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;

import mes.app.MailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.UserRepository;
import mes.domain.security.CustomAuthenticationToken;
import mes.domain.security.Pbkdf2Sha256;
import mes.domain.services.AccountService;
import mes.domain.services.SqlRunner;


@RestController
public class AccountController {
	
	@Autowired
	AccountService accountService;
		
    @Autowired
    UserRepository userRepository;
	
	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	MailService emailService;

	private final ConcurrentHashMap<String, String> tokenStore = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> tokenExpiry = new ConcurrentHashMap<>();
	private Boolean flag;
	private Boolean flag_pw;
	
	@Resource(name="authenticationManager")
    private AuthenticationManager authManager;
	
	@GetMapping("/login")
    public ModelAndView loginPage(
    		HttpServletRequest request,
    		HttpServletResponse response,
    		HttpSession session, Authentication auth) {

		// ✅ 1️⃣ 자동로그인 쿠키 검사
		if (auth == null) {
			Cookie[] cookies = request.getCookies();
			if (cookies != null) {
				for (Cookie cookie : cookies) {
					if ("MES_AUTO_LOGIN".equals(cookie.getName())) {
						String username = cookie.getValue();

						User user = userRepository.findByUsername(username).orElse(null);

						if (user != null && user.getActive()) {
							UsernamePasswordAuthenticationToken token =
									new UsernamePasswordAuthenticationToken(
											user, null, Collections.emptyList());

							SecurityContextHolder.getContext().setAuthentication(token);
							session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

							return new ModelAndView("redirect:/");
						} else {
							Cookie clearCookie = new Cookie("MES_AUTO_LOGIN", null);
							clearCookie.setMaxAge(0);
							clearCookie.setPath("/");
							response.addCookie(clearCookie);
						}
					}

				}
			}
		}

		//User-Agent를 기반으로 모바일 여부 감지
		String userAgent = request.getHeader("User-Agent").toLowerCase();
		boolean isMobile = userAgent.contains("mobile") || userAgent.contains("android") || userAgent.contains("iphone");

		String serverName = request.getServerName();

//		if (isMobile && serverName.equalsIgnoreCase("actascld.co.kr")) {
//			String redirectUrl = "https://mes.actascld.co.kr";
//			try {
//				response.sendRedirect(redirectUrl);
//				return null; // redirect 했으므로 이후 처리 중단
//			} catch (IOException e) {
//				e.printStackTrace(); // 로그로 출력하거나, 에러 뷰로 포워딩도 가능
//				return new ModelAndView("error/redirect_error"); // 예외 시 fallback 처리
//			}
//		}

		// 세션을 이용해 모바일에서 한 번만 리디렉션되도록 설정
		Boolean isMobileRedirected = (Boolean) session.getAttribute("isMobileRedirected");

		if (isMobile && (isMobileRedirected == null || !isMobileRedirected)) {
			session.setAttribute("isMobileRedirected", true);  // ✅ 모바일에서 리디렉션 상태 저장
			return new ModelAndView("redirect:/MobileFirstPage");
		}

		// 모바일이면 "mlogin" 뷰 반환, 웹이면 "login" 뷰 반환
		ModelAndView mv = new ModelAndView(isMobile ? "mlogin" : "login");
		
		Map<String, Object> userInfo = new HashMap<String, Object>(); 
		Map<String, Object> gui = new HashMap<String, Object>();
		
		mv.addObject("userinfo", userInfo);
		mv.addObject("gui", gui);
		if(auth!=null) {
			SecurityContextLogoutHandler handler =  new SecurityContextLogoutHandler();
			handler.logout(request, response, auth);
		}
		
		return mv;
	}

	@GetMapping("/MobileFirstPage")
	public ModelAndView mobileFirstPage(HttpSession session) {
		session.removeAttribute("isMobileRedirected");  // ✅ 모바일 첫 페이지에서 세션 값 초기화
		return new ModelAndView("/mobile/MobileFirstPage");
	}
	
	@GetMapping("/logout")
	public void logout(
			HttpServletRequest request
			, HttpServletResponse response) throws IOException {
		
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();		
		SecurityContextLogoutHandler handler =  new SecurityContextLogoutHandler();
		
		this.accountService.saveLoginLog("logout", auth);
		
		handler.logout(request, response, auth);

		// ✅ 자동로그인 쿠키 제거
		Cookie clearCookie = new Cookie("MES_AUTO_LOGIN", null);
		clearCookie.setMaxAge(0);     // 즉시 만료
		clearCookie.setPath("/");     // 전체 경로 적용
		response.addCookie(clearCookie);

	    response.sendRedirect("/login");
	}

    @PostMapping("/login")
    public AjaxResult postLogin(
    		@RequestParam("username") final String username, 
    		@RequestParam("password") final String password,
			@RequestParam(value = "autoLogin", required = false) String autoLogin,
    		final HttpServletRequest request,
			HttpServletResponse response) {
    	// 여기로 들어오지 않음.
    	
    	AjaxResult result = new AjaxResult();
    	
    	HashMap<String, Object> data = new HashMap<String, Object>();
    	result.data = data;
    	
        UsernamePasswordAuthenticationToken authReq = new UsernamePasswordAuthenticationToken(username, password);
		CustomAuthenticationToken auth = null;

		try{
			auth = (CustomAuthenticationToken)authManager.authenticate(authReq);


		} catch (InsufficientAuthenticationException e) {
			data.put("code", "null");
			return result;
		}catch (AuthenticationException e){
			//e.printStackTrace();
			data.put("code", "NOUSER");
			return result;
		}


		if(auth!=null) {
			User user = (User)auth.getPrincipal();

			if (!user.getActive()) {  // user.getActive()가 false인 경우
				data.put("code", "noactive");
			} else {
				data.put("code", "OK");

				try {
					this.accountService.saveLoginLog("login", auth);
				} catch (UnknownHostException e) {
					// Handle the exception (e.g., log it)
					e.printStackTrace();
				}
				// 자동 로그인
				if ("on".equals(autoLogin)) {
					Cookie autoLoginCookie = new Cookie("MES_AUTO_LOGIN", username);
					autoLoginCookie.setHttpOnly(true);
					autoLoginCookie.setPath("/");
					autoLoginCookie.setMaxAge(60 * 60 * 24 * 365); // 자동 로그인
					response.addCookie(autoLoginCookie);
				}
			}
		} else {
			result.success=false;
			data.put("code", "NOID");
		}

		SecurityContext sc = SecurityContextHolder.getContext();
		sc.setAuthentication(auth);

		HttpSession session = request.getSession(true);
		session.setAttribute("SPRING_SECURITY_CONTEXT", sc);

		return result;
	}

	@GetMapping("/account/myinfo")
	public AjaxResult getUserInfo(Authentication auth){
		User user = (User)auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Map<String, Object> dicData = new HashMap<String, Object>();
		dicData.put("login_id", user.getUsername());
		dicData.put("name", user.getUserProfile().getName());
		dicData.put("userHp", user.getTel());
		dicData.put("email", user.getEmail());
		result.data = dicData;
		return result;
	}

    @PostMapping("/account/myinfo/password_change")
    public AjaxResult userPasswordChange(
    		@RequestParam("name") final String name,
    		@RequestParam("loginPwd") final String loginPwd,
    		@RequestParam("loginPwd2") final String loginPwd2,
    		Authentication auth
    		) {

    	User user = (User)auth.getPrincipal();
        AjaxResult result = new AjaxResult();

        if (StringUtils.hasText(loginPwd)==false | StringUtils.hasText(loginPwd2)==false) {
        	result.success=false;
        	result.message="The verification password is incorrect.";
        	return result;
        }

        if(loginPwd.equals(loginPwd2)==false) {
        	result.success=false;
        	result.message="The verification password is incorrect.";
        	return result;
        }

        user.setPassword(Pbkdf2Sha256.encode(loginPwd2));
        //user.getUserProfile().setName(name);
        this.userRepository.save(user);

        String sql = """
        	update user_profile set 
        	"Name"=:name, _modified = now(), _modifier_id=:id 
        	where id=:id 
        """;

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("name", name);
        dicParam.addValue("id", user.getId());
        this.sqlRunner.execute(sql, dicParam);


        return result;
    }

	/***
	 *  아이디 중복 확인
	 * **/
	@PostMapping("/useridchk")
	public AjaxResult IdChk(@RequestParam("userid") final String userid){

		AjaxResult result = new AjaxResult();


		Optional<User> user = userRepository.findByUsername(userid);


		if(!user.isPresent()){

			result.success = true;
			result.message = "사용할 수 있는 계정입니다.";
			return result;

		}else {
			result.success = false;
			result.message = "중복된 계정이 존재합니다.";
			return result;
		}


	}

	@PostMapping("/authentication")
	public AjaxResult Authentication(@RequestParam(value = "AuthenticationCode") String AuthenticationCode,
									 @RequestParam(value = "email", required = false) String email,
									 @RequestParam String type
	){

		AjaxResult result = verifyAuthenticationCode(AuthenticationCode, email);

		if(type.equals("new")){
			if(result.success){
				flag = true;
				result.message = "인증되었습니다.";

			}

		}else{
			if(result.success){
				flag_pw = true;
				result.message = "인증되었습니다.";
			}
		}

		return result;
	}

	private AjaxResult verifyAuthenticationCode(String code, String mail){

		AjaxResult result = new AjaxResult();

		String storedToken = tokenStore.get(mail);
		if(storedToken != null && storedToken.equals(code)){
			long expiryTime = tokenExpiry.getOrDefault(mail, 0L);
			if(System.currentTimeMillis() > expiryTime){
				result.success = false;
				result.message = "인증 코드가 만료되었습니다.";
				tokenStore.remove(mail);
				tokenExpiry.remove(mail);
			} else {
				result.success = true;
				result.message = "비밀번호가 변경되었습니다.";
			}
		}else{
			result.success = false;
			result.message = "인증 코드가 유효하지 않습니다.";
		}
		return result;
	}


	@PostMapping("/user-auth/AuthenticationEmail")
	public AjaxResult PwSearch(@RequestParam(value = "usernm", required = false) final String usernm,
							   @RequestParam("mail") final String mail,
							   @RequestParam("content") final String content,
							   @RequestParam String type
	){

		AjaxResult result = new AjaxResult();

		if(type.equals("new")){
			if(!usernm.isEmpty() && type.equals("new")){
				sendEmailLogic(mail, usernm, content);

				result.success = true;
				result.message = "인증 메일이 발송되었습니다.";
				return result;
			}
			return result;
		}else{
			boolean flag = userRepository.existsByUsernameAndEmail(usernm, mail);

			if(flag) {
				sendEmailLogic(mail, usernm, content);

				result.success = true;
				result.message = "인증 메일이 발송되었습니다.";
			}else {
				result.success = false;
				result.message = "해당 사용자가 존재하지 않습니다.";
			}

			return result;
		}


	}

	private void sendEmailLogic(String mail, String usernm, String content){
		Random random = new Random();
		int randomNum = 100000 + random.nextInt(900000); // 100000부터 999999까지의 랜덤 난수 생성
		String verificationCode = String.valueOf(randomNum); // 정수를 문자열로 변환
		emailService.sendVerificationEmail(mail, usernm, verificationCode, content);

		tokenStore.put(mail, verificationCode);
		tokenExpiry.put(mail, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3));

	}




	@PostMapping("/user-auth/save")
	@Transactional
	public AjaxResult saveUser(
			@RequestParam(value="idx", required = false) Integer id,
			@RequestParam(value="name") String Name,		//이름 (user_profile.Name)
			@RequestParam(value="id") String login_id, //사번 (auth_user.username)
			@RequestParam(value="email", required = false, defaultValue = "") String email,
			@RequestParam(value="Factory_id", required = false) Integer Factory_id,
			@RequestParam(value="Depart_id", required = false) Integer Depart_id,
			@RequestParam(value="UserGroup_id", required = false) Integer UserGroup_id,
			@RequestParam(value="lang_code", required = false) String lang_code,
			@RequestParam(value="is_active", required = false) Boolean is_active,
			@RequestParam(value="password") String password,
			@RequestParam(value="tel", required = false) String tel,
			@RequestParam(value="spjangcd") String spjangcd,
			HttpServletRequest request,
			Authentication auth
	) {

		AjaxResult result = new AjaxResult();

		// 기본값 지정
		if (Factory_id == null) {
			Factory_id = 1;
		}
		if (Depart_id == null) {
			Depart_id = 1;
		}
		if (UserGroup_id == null) {
			UserGroup_id = 2;
		}


		String sql = null;
		User user = null;

		Timestamp today = new Timestamp(System.currentTimeMillis());
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		boolean username_chk = this.userRepository.findByUsername(login_id).isEmpty();

		if(is_active == null) {
			is_active = false;
		}


		// new data일 경우
		if (id==null) {
			if (username_chk == false) {
				result.success = false;
				result.message="중복된 사번이 존재합니다.";
				return result;
			}
			user = new User();
			String encodedPassword = Pbkdf2Sha256.encode(password);
			user.setPassword(encodedPassword);
			user.setSuperUser(false);
			user.setLast_name("");
			user.setIs_staff(false);

			sql = """
		        	INSERT INTO user_profile 
		        	("_created", "_creater_id", "User_id", "lang_code", "Name", "Factory_id" , "Depart_id", "UserGroup_id" ) 
		        	VALUES (now(), :loginUser, :User_id, :lang_code, :name, :Factory_id, :Depart_id, :UserGroup_id )
		        """;
		}

		user.setUsername(login_id);
		user.setFirst_name(Name);
		user.setEmail(email);
		user.setTel(tel);
		user.setDate_joined(today);
		user.setActive(is_active);
		user.setSpjangcd(spjangcd);


		user = this.userRepository.save(user);

		dicParam.addValue("name", Name);
		dicParam.addValue("UserGroup_id", UserGroup_id);
		dicParam.addValue("Factory_id", Factory_id);
		dicParam.addValue("Depart_id", Depart_id);
		dicParam.addValue("lang_code", lang_code);

		this.sqlRunner.execute(sql, dicParam);

		result.data = user;

		return result;
	}


	@PostMapping("/user-auth/searchAccount")
	public AjaxResult IdSearch(@RequestParam("usernm") final String usernm,
							   @RequestParam("mail") final String mail){

		AjaxResult result = new AjaxResult();

		List<String> user = userRepository.findByFirstNameAndEmailNative(usernm, mail);

		if(!user.isEmpty()){
			result.success = true;
			result.data = user;
		}else {
			result.success = false;
			result.message = "해당 사용자가 존재하지 않습니다.";
		}
		return result;
	}



	@PostMapping("/user-auth/getspjangcd")
	public AjaxResult getspjangcd(){

		AjaxResult result = new AjaxResult();

		List<Map<String, String>> list = accountService.findspjangcd();

		result.data = list;
		return result;
	}

	@PostMapping("/account/myinfosave")
	public AjaxResult setUserInfo(
			@RequestParam("name") final String name,
			@RequestParam("loginPwd") final String loginPwd,
			@RequestParam("loginPwd2") final String loginPwd2,
			@RequestParam("userHp") final String userHp,
			Authentication auth
	) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		AjaxResult result = new AjaxResult();
		User user = (User)auth.getPrincipal();

		if (StringUtils.hasText(loginPwd)==false | StringUtils.hasText(loginPwd2)==false) {
			result.success=false;
			result.message="The verification password is incorrect.";
			return result;
		}

		if(loginPwd.equals(loginPwd2)==false) {
			result.success=false;
			result.message="비밀번호와 확인이 서로 맞지않습니다.";
			return result;
		}

		String encodedPWD = Pbkdf2Sha256.encode(loginPwd2);
		if(name != null && !name.isEmpty()) {
			dicParam.addValue("name", name);
		}
		if(userHp != null && !userHp.isEmpty()) {
			dicParam.addValue("userHp", userHp);
		}
		if(loginPwd2 != null && !loginPwd2.isEmpty()) {
			dicParam.addValue("encodedPWD", encodedPWD);
		}
		//user.getUserProfile().setName(name);
		String authSql = """
        	update auth_user set 
        	password = :encodedPWD, tel = :userHp, first_name = :name 
        	where id=:id 
        """;

		String profileSql = """
        	update user_profile set 
        	"Name"=:name, _modified = now(), _modifier_id=:id 
        	where "User_id"=:id 
        """;

		String personSql = """
        	update person set 
        	"Name"=:name, _modified = now(), _modifier_id=:id 
        	where id=:personid 
        """;


		dicParam.addValue("name", name);
		dicParam.addValue("id", user.getId());
		dicParam.addValue("personid", user.getPersonid());
		this.sqlRunner.execute(authSql, dicParam);
		this.sqlRunner.execute(profileSql, dicParam);
		this.sqlRunner.execute(personSql, dicParam);

		result.message="사용자 정보가 수정되었습니다.\n다시 로그인하여 주십시오";


		return result;
	}
}