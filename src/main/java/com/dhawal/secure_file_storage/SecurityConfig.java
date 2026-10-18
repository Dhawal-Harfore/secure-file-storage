
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
                    "/api/auth/verify"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                "/api/auth/magic-link",
                "/api/auth/verify"
            ));

        return http.build();
    }
}