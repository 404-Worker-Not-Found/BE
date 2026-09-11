package com.workernotfound.matching.global.config;

import com.workernotfound.matching.external.client.job.JobServiceProperties;
import com.workernotfound.matching.external.client.member.MemberServiceProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({MemberServiceProperties.class, JobServiceProperties.class})
public class RestClientConfig {

	@Bean
	public RestClient memberServiceRestClient(MemberServiceProperties properties) {
		return createInternalRestClient(
			properties.baseUrl(),
			properties.connectTimeout(),
			properties.readTimeout(),
			properties.internalSecret()
		);
	}

	@Bean
	public RestClient jobServiceRestClient(JobServiceProperties properties) {
		return createInternalRestClient(
			properties.baseUrl(),
			properties.connectTimeout(),
			properties.readTimeout(),
			properties.internalSecret()
		);
	}

	private RestClient createInternalRestClient(
		String baseUrl,
		Duration connectTimeout,
		Duration readTimeout,
		String internalSecret
	) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeout);
		requestFactory.setReadTimeout(readTimeout);

		return RestClient.builder()
			.baseUrl(baseUrl)
			// TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
			.defaultHeader("X-Internal-Secret", internalSecret)
			.requestFactory(requestFactory)
			.build();
	}
}
