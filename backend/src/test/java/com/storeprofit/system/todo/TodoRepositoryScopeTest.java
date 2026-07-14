package com.storeprofit.system.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeprofit.system.todo.BusinessTodoRepository.BusinessTodoRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TodoRepositoryScopeTest {
  @Test
  void businessTodoListAddsRoleAndStorePredicatesBeforeLimit() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    BusinessTodoRepository repository = new BusinessTodoRepository(jdbcTemplate, named);
    repository.listVisible(1L, null, 50, "FINANCE", false, false, List.of("s1", "s2"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(named).query(sql.capture(), params.capture(), org.mockito.ArgumentMatchers.<RowMapper<BusinessTodoRow>>any());
    assertThat(sql.getValue()).contains("upper(t.assignee_role) = :role", "t.store_id in (:storeIds)");
    assertThat(params.getValue().getValue("role")).isEqualTo("FINANCE");
    assertThat(params.getValue().getValue("storeIds")).isEqualTo(List.of("s1", "s2"));
  }

  @Test
  void dataImportIssuesNeverReadGlobalKvForAnotherTenant() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    RoleTodoRepository repository = new RoleTodoRepository(jdbcTemplate, named);

    assertThat(repository.dataImportIssues(2L, 50)).isEmpty();

    verifyNoInteractions(named);
  }
}
