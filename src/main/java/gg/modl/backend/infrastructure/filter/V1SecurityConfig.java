package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.cors.DynamicCorsConfigurationSource;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class V1SecurityConfig {
    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final ApiKeyFilter apiKeyFilter;
    private final AdminAuthFilter adminAuthFilter;
    private final PanelPermissionFilter panelPermissionFilter;
    private final OriginCsrfFilter originCsrfFilter;
    private final DynamicCorsConfigurationSource dynamicCorsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .cors(cors -> cors.configurationSource(dynamicCorsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                )
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c -> c
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/v1").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers(RESTMappingV1.HEALTH).permitAll()
                .requestMatchers(RESTMappingV1.PREFIX_PUBLIC + "/**").permitAll()
                .requestMatchers(RESTMappingV1.PANEL_AUTH + "/**").permitAll()
                .requestMatchers(RESTMappingV1.ADMIN_AUTH + "/**").permitAll()
                .requestMatchers(RESTMappingV1.WEBHOOKS_STRIPE + "/**").permitAll()
                .requestMatchers(RESTMappingV1.REALTIME_WS).permitAll()
                .requestMatchers(RESTMappingV1.PREFIX_ADMIN + "/**").hasAuthority(RESTSecurityRole.ADMIN)
                .requestMatchers(RESTMappingV1.PREFIX_PANEL + "/**").hasAuthority(RESTSecurityRole.USER)
                .requestMatchers(RESTMappingV1.PREFIX_REPLAY_LITE + "/**").hasAuthority(RESTSecurityRole.MINECRAFT)
                .requestMatchers(RESTMappingV1.PREFIX_MINECRAFT + "/**").hasAuthority(RESTSecurityRole.MINECRAFT)
                .requestMatchers(RESTMappingV2.PREFIX_MINECRAFT + "/**").hasAuthority(RESTSecurityRole.MINECRAFT)
                .requestMatchers(RESTMappingV3.PREFIX_MINECRAFT + "/**").hasAuthority(RESTSecurityRole.MINECRAFT)
                .anyRequest().authenticated()
            )
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(panelPermissionFilter, SessionAuthenticationFilter.class)
            .addFilterAfter(originCsrfFilter, AdminAuthFilter.class)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .build();
    }
}
