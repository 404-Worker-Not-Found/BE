package com.workernotfound.auth.global.config;

import com.workernotfound.auth.external.client.member.MemberServiceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MemberServiceProperties.class)
public class RestClientConfig {

	@Bean
	public RestClient memberServiceRestClient(MemberServiceProperties memberServiceProperties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(memberServiceProperties.connectTimeout());
		requestFactory.setReadTimeout(memberServiceProperties.readTimeout());

		return RestClient.builder()
			.baseUrl(memberServiceProperties.baseUrl())
			.requestFactory(requestFactory)
			.build();
	}
}
