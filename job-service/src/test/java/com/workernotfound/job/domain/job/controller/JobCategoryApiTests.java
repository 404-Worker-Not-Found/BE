package com.workernotfound.job.domain.job.controller;

import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 클라이언트는 공고 등록·검색에 쓸 활성 업종 카테고리를 인증 없이 대분류·하위 분류 구조로 받는다.
@AutoConfigureMockMvc
class JobCategoryApiTests extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> insertedCategoryIds = new ArrayList<>();

    @AfterEach
    void deleteInsertedCategories() {
        // 하위 분류가 먼저 들어가므로 역순으로 지운다.
        for (int index = insertedCategoryIds.size() - 1; index >= 0; index--) {
            jdbcTemplate.update("DELETE FROM industry_categories WHERE id = ?", insertedCategoryIds.get(index));
        }
        insertedCategoryIds.clear();
    }

    @Test
    void returnsSeededCategoriesAsParentsWithSubCategoriesWithoutToken() throws Exception {
        String body = mockMvc.perform(get("/api/jobs/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].id").value(1))
                .andExpect(jsonPath("$.data.categories[0].name").value("외식/음료"))
                .andReturn().getResponse().getContentAsString();

        assertThat(parentNames(body)).containsExactly(
                "외식/음료", "유통/판매", "물류/배송", "서비스", "사무/행정", "교육", "기타");
        assertThat(subCategoryNames(body, "외식/음료")).containsExactly("카페/음료", "패스트푸드", "일반음식점");
        assertThat(subCategoryNames(body, "서비스")).containsExactly("미용/뷰티", "청소/시설", "돌봄/케어");
        assertThat(subCategoryNames(body, "교육")).isEmpty();
        List<Integer> topLevelIds = JsonPath.read(body, "$.data.categories[*].id");
        assertThat(topLevelIds).isSorted();
    }

    @Test
    void excludesInactiveCategoriesAndSubCategoriesOfInactiveParents() throws Exception {
        long inactiveParent = insertCategory("비활성 대분류", null, false);
        insertCategory("비활성 대분류의 활성 하위", inactiveParent, true);
        long activeParent = insertCategory("활성 대분류", null, true);
        insertCategory("활성 대분류의 비활성 하위", activeParent, false);
        insertCategory("활성 대분류의 활성 하위", activeParent, true);

        String body = mockMvc.perform(get("/api/jobs/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(parentNames(body)).contains("활성 대분류").doesNotContain("비활성 대분류");
        assertThat(subCategoryNames(body, "활성 대분류")).containsExactly("활성 대분류의 활성 하위");
        List<String> allSubCategoryNames = JsonPath.read(body, "$.data.categories[*].subCategories[*].name");
        assertThat(allSubCategoryNames).doesNotContain("비활성 대분류의 활성 하위", "활성 대분류의 비활성 하위");
    }

    @Test
    void invalidTokenIsIgnoredLikeOtherPublicJobReads() throws Exception {
        mockMvc.perform(get("/api/jobs/categories").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].name").value("외식/음료"));
    }

    private long insertCategory(String name, Long parentId, boolean isActive) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO industry_categories (created_at, updated_at, name, parent_id, is_active) "
                            + "VALUES (NOW(6), NOW(6), ?, ?, ?)",
                    new String[]{"id"});
            statement.setString(1, name);
            statement.setObject(2, parentId);
            statement.setBoolean(3, isActive);
            return statement;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        insertedCategoryIds.add(id);
        return id;
    }

    private List<String> parentNames(String body) {
        return JsonPath.read(body, "$.data.categories[*].name");
    }

    private List<String> subCategoryNames(String body, String parentName) {
        return JsonPath.read(body, "$.data.categories[?(@.name == '" + parentName + "')].subCategories[*].name");
    }
}
