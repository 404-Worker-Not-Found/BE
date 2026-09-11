package com.workernotfound.matching;

import com.workernotfound.matching.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@AutoConfigureMockMvc
class SwaggerDocumentationTests extends IntegrationTestSupport {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void openApiDocsAreAccessible() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.schemas.ApiResponseApplicationResponse.properties.success").exists())
			.andExpect(jsonPath("$.components.schemas.ApiResponseApplicationResponse.properties.data").exists())
			.andExpect(jsonPath("$.components.schemas.ApiResponseApplicationListResponse.properties.data").exists());
	}

	@Test
	void swaggerUiIsAccessible() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
			.andExpect(status().isOk());
	}
}
