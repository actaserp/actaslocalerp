package mes.config;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import mes.domain.security.AjaxAwareLoginUrlAuthenticationEntryPoint;

import mes.domain.security.CustomAccessDeniedHandler;
import mes.domain.security.CustomAuthenticationFailureHandler;
import mes.domain.security.CustomAuthenticationManager;
import mes.domain.security.CustomAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value;


@Configuration
@ComponentScan("mes.domain.security")
public class SecurityConfiguration {
	
	@Autowired
	private CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler;

	@Autowired
	private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @Value("${server.servlet.session.cookie.name}")
    private String sessionCookieName;
		
	@Bean(name="authenticationManager")	
	CustomAuthenticationManager authenticationManager() {
		CustomAuthenticationManager authenticationManager = new CustomAuthenticationManager();
		return authenticationManager;
	}

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        http
                .csrf(csrf -> csrf
                        .ignoringAntMatchers("/api/files/upload/**", "/popbill/webhook")
                );

        http
                .authorizeRequests(auth -> auth
                        .antMatchers(
                                "/login",
                                "/logout",
                                "/resource/**",
                                "/img/**",
                                "/images/**",
                                "/js/**",
                                "/css/**",
                                "/assets_mobile/**",
                                "/font/**",
                                "/robots.txt",
                                "/favicon.ico"
                        ).permitAll()
                        .anyRequest().authenticated()
                );

        http
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/postLogin")
                        .permitAll()
                );

        http
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login")
                );

        return http.build();
    }


}

