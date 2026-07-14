package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.operations.ExamCenterModels.ExamAnswerInput;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamPublishRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamQuestionSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamSubmissionRequest;
import com.storeprofit.system.operations.ExamLearningModels.AttemptReviewRequest;
import com.storeprofit.system.operations.ExamLearningModels.CourseRequest;
import com.storeprofit.system.operations.ExamLearningModels.MaterialRequest;
import com.storeprofit.system.operations.ExamLearningModels.QuestionBankRequest;
import com.storeprofit.system.operations.ExamLearningModels.QuestionCategoryRequest;
import com.storeprofit.system.operations.ExamLearningModels.ReviewAnswerRequest;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ExamLearningFlowTest {
  @Test
  void createQuestionAssemblePublishSubmitReviewAndResultPersist() {
    DataSource dataSource = migratedDataSource();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    ObjectMapper objectMapper = new ObjectMapper();
    seedUsers(jdbc);

    AccessControlService access = mock(AccessControlService.class);
    AuditRepository audit = mock(AuditRepository.class);
    ExamLearningRepository learningRepository = new ExamLearningRepository(jdbc, objectMapper);
    ExamCenterRepository centerRepository = new ExamCenterRepository(jdbc, objectMapper);
    OperationsBusinessRepository operationsRepository = new OperationsBusinessRepository(jdbc, objectMapper);
    ExamLearningService learningService = new ExamLearningService(access, learningRepository, audit);
    ExamCenterService centerService = new ExamCenterService(
        centerRepository, operationsRepository, access, audit, learningRepository);

    AuthUser operator = user(100L, "OPERATIONS", null, "TEST考试管理员");
    AuthUser employee = user(101L, "EMPLOYEE", "TEST_STORE", "TEST员工");
    when(access.dataScope(operator, DataScopeDomains.EXAM)).thenReturn(DataScope.all());
    when(access.dataScope(employee, DataScopeDomains.EXAM))
        .thenReturn(new DataScope(DataScopeModes.SELF, List.of()));

    var material = learningService.saveMaterial(operator, new MaterialRequest(
        null, "TEST_MATERIAL", "TEST食品安全资料", "食品安全", List.of(),
        "TEST环境学习资料", true, 1));
    var course = learningService.saveCourse(operator, new CourseRequest(
        null, "TEST_COURSE", "TEST食品安全课程", "食品安全", "TEST完整课程",
        null, 30, "EMPLOYEE", true, 1, List.of(material.id())));
    var category = learningService.saveQuestionCategory(operator, new QuestionCategoryRequest(
        null, "TEST_CATEGORY", "TEST题目分类", "TEST分类", true, 1));
    var bankQuestion = learningService.saveQuestion(operator, new QuestionBankRequest(
        null, "TEST_SINGLE", category.id(), "SINGLE_CHOICE", "TEST正确操作是什么？",
        List.of("正确", "错误"), "正确", "选择正确操作", null, "EASY",
        BigDecimal.valueOf(40), true));

    assertThat(course.materialIds()).containsExactly(material.id());
    assertThat(bankQuestion.categoryName()).isEqualTo("TEST题目分类");

    var paper = centerService.savePaper(operator, new ExamPaperSaveRequest(
        null,
        "TEST_PAPER",
        "TEST全流程试卷",
        "EMPLOYEE",
        BigDecimal.valueOf(50),
        true,
        List.of(
            new ExamQuestionSaveRequest(
                bankQuestion.id(), "SINGLE_CHOICE", bankQuestion.questionText(), bankQuestion.options(),
                bankQuestion.standardAnswer(), null, BigDecimal.valueOf(40)),
            new ExamQuestionSaveRequest(
                "ESSAY", "TEST请说明食品安全处理步骤", List.of(),
                "停止使用、隔离并上报", null, BigDecimal.valueOf(60))
        )
    ));
    LocalDateTime now = LocalDateTime.now();
    var campaign = centerService.publish(operator, new ExamPublishRequest(
        paper.id(), "TEST食品安全考试", now.minusMinutes(5).toString(), now.plusDays(1).toString(),
        List.of("TEST_STORE"), List.of("EMPLOYEE"), List.of()
    ));
    var assignment = campaign.assignments().getFirst();
    var questions = operationsRepository.examPaper(1L, paper.id(), true).orElseThrow().questions();

    var attempt = centerService.submit(employee, assignment.id(), new ExamSubmissionRequest(
        false,
        List.of(
            new ExamAnswerInput(questions.get(0).id(), "错误"),
            new ExamAnswerInput(questions.get(1).id(), "停止使用并上报")
        )
    ));

    assertThat(attempt.score()).isEqualByComparingTo("0.00");
    assertThat(centerRepository.assignment(1L, assignment.id(), false).orElseThrow().status())
        .isEqualTo("REVIEW_PENDING");
    var review = learningService.reviewDetail(operator, attempt.id());
    assertThat(review.answers()).hasSize(2);

    List<ReviewAnswerRequest> reviewedAnswers = review.answers().stream()
        .map(answer -> new ReviewAnswerRequest(
            answer.answerId(),
            "ESSAY".equals(answer.questionType()) ? BigDecimal.valueOf(60) : BigDecimal.ZERO,
            "TEST阅卷评语"))
        .toList();
    learningService.review(operator, attempt.id(), new AttemptReviewRequest("TEST阅卷完成", reviewedAnswers));

    var result = learningService.results(employee).getFirst();
    assertThat(result.score()).isEqualByComparingTo("60.00");
    assertThat(result.passed()).isTrue();
    assertThat(result.reviewStatus()).isEqualTo("REVIEWED");
    assertThat(learningService.wrongQuestions(employee))
        .singleElement()
        .satisfies(wrong -> assertThat(wrong.questionText()).isEqualTo("TEST正确操作是什么？"));
  }

  private DataSource migratedDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:exam_flow;MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();
    return dataSource;
  }

  private void seedUsers(JdbcTemplate jdbc) {
    jdbc.update("""
        insert into store_branch(id, tenant_id, name, status, created_at)
        values ('TEST_STORE', 1, 'TEST门店', '营业中', current_timestamp)
        """);
    jdbc.update("""
        insert into auth_user(id, tenant_id, username, password_hash, display_name, role, store_id, enabled, created_at)
        values (100, 1, 'test_exam_operator', 'TEST_HASH', 'TEST考试管理员', 'OPERATIONS', null, 1, current_timestamp)
        """);
    jdbc.update("""
        insert into auth_user(id, tenant_id, username, password_hash, display_name, role, store_id, enabled, created_at)
        values (101, 1, 'test_exam_employee', 'TEST_HASH', 'TEST员工', 'EMPLOYEE', 'TEST_STORE', 1, current_timestamp)
        """);
  }

  private AuthUser user(long id, String role, String storeId, String name) {
    return new AuthUser(id, 1L, "TEST租户", "test-" + id, "", name, role, storeId, true);
  }
}
