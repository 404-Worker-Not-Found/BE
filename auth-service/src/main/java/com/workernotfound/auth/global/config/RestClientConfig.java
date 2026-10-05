package com.workernotfound.auth.global.config;

import com.workernotfound.auth.external.client.member.MemberServiceProperties;
import com.workernotfound.auth.external.client.oauth.OAuth2ClientProperties;
import com.workernotfound.auth.domain.auth.service.VerificationDeliveryProperties;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({MemberServiceProperties.class, OAuth2ClientProperties.class, VerificationDeliveryProperties.class})
public class RestClientConfig {

	@Bean
	public RestClient memberServiceRestClient(MemberServiceProperties memberServiceProperties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(memberServiceProperties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(memberServiceProperties.readTimeout());

		return RestClient.builder()
			.baseUrl(memberServiceProperties.baseUrl())
			// TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
			.defaultHeader("X-Internal-Secret", memberServiceProperties.internalSecret())
			.requestFactory(requestFactory)
			.build();
	}

	@Bean
	public RestClient oauthProviderRestClient(OAuth2ClientProperties oauth2ClientProperties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(oauth2ClientProperties.connectTimeout());
		requestFactory.setReadTimeout(oauth2ClientProperties.readTimeout());

		return RestClient.builder()
			.requestFactory(requestFactory)
			.build();
	}

	@Bean
	public RestClient verificationDeliveryRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(3));
		requestFactory.setReadTimeout(java.time.Duration.ofSeconds(5));
		return RestClient.builder().requestFactory(requestFactory).build();
	}
}
