
package com.dhawal.secure_file_storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
                    "/css/**",
                    "/js/**",
                    "/vendor/**",
                    "/api/auth/magic-link",
                    "/api/auth/verify",
                    "/api/auth/session",
                    "/api/auth/logout"
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
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                "/api/auth/magic-link",
                "/api/auth/verify",
                "/api/auth/logout"
            ));

        return http.build();
    }
}