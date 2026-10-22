
package com.dhawal.secure_file_storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/login.html",
                    "/unlock.html",
                    "/vault.html",
                    "/share.html",
                    "/css/**",
                    "/js/**",
                    "/vendor/**",
                    "/api/auth/magic-link",
                    "/api/auth/verify",
                    "/api/auth/session",
                    "/api/auth/logout",
                    "/api/s/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, e) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"error\":{\"code\":\"unauthenticated\","
                        + "\"message\":\"Your session has ended. Sign in again.\",\"reason\":\"\"}}");
                })
            )
            .csrf(csrf -> csrf.spa().ignoringRequestMatchers(
                "/api/auth/magic-link",
                "/api/auth/verify",
                "/api/auth/logout"
            ))
            .addFilterAfter((request, response, chain) -> {
                CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (token != null) {
                    token.getToken();
                }
                chain.doFilter(request, response);
            }, CsrfFilter.class);

        return http.build();
    }
}