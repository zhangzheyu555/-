package com.storeprofit.system.boss;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BossDataHealthService {
  private static final Set<String> OWNER_ROLES = Set.of("BOSS", "ADMIN", "OPERATIONS");
  private static final String SERVER_RULES = "SERVER_RULES";
  private static final String MYSQL = "MYSQL";
  private static final String KV = "KV";

  public BossDataHealthResponse dataHealth(AuthUser user) {
    requireOwner(user);
    String checkedAt = OffsetDateTime.now().toString();
    return new BossDataHealthResponse(
        SERVER_RULES,
        checkedAt,
        List.of(
            module(
                "今日待办",
                MYSQL,
                "MySQL结构化",
                checkedAt,
                "老板首页、角色待办、顶部统计数字",
                "前端已接 /api/boss/data-health；待办事项后续按角色拆成标准接口",
                "优先补齐老板、财务、督导、仓库、店长的分角色待办接口"
            ),
            module(
                "利润",
                KV,
                "兼容KV",
                checkedAt,
                "门店月利润、利润异常、老板利润看板",
                "当前仍有兼容历史数据入口，需要逐步收口到 profit_entry",
                "先保证真实利润录入和导入全部写入 MySQL，再下线旧浏览器存储"
            ),
            module(
                "报销",
                KV,
                "兼容KV",
                checkedAt,
                "门店报销提交、财务审核、老板追踪",
                "历史报销数据存在兼容迁移路径，处理页需要继续接标准接口",
                "把提交、审核、驳回、原因记录统一落到 MySQL"
            ),
            module(
                "督导巡店",
                KV,
                "兼容KV",
                checkedAt,
                "巡店记录、整改跟踪、督导处理",
                "巡店模块有历史数据兼容入口，待整改闭环还需要后端状态流转",
                "优先固化巡店结果、整改状态、处理人和处理时间"
            ),
            module(
                "仓库",
                KV,
                "兼容KV",
                checkedAt,
                "叫货单、仓库处理、采购申请",
                "仓库 MVP 已有业务入口，但叫货处理链路还需标准 MySQL 接口兜住",
                "先实现门店叫货、仓库确认、采购申请三段状态"
            ),
            module(
                "工资",
                KV,
                "兼容KV",
                checkedAt,
                "工资记录、门店工资汇总、老板查看",
                "工资数据仍处于兼容状态，需要确认真实录入来源",
                "把真实工资记录写入 salary_record，并保留导入校验"
            )
        )
    );
  }

  private BossDataHealthModuleResponse module(
      String moduleName,
      String status,
      String dataSource,
      String lastUpdatedAt,
      String businessScope,
      String migrationNote,
      String recommendation
  ) {
    return new BossDataHealthModuleResponse(
        moduleName,
        status,
        dataSource,
        lastUpdatedAt,
        businessScope,
        migrationNote,
        recommendation
    );
  }

  private void requireOwner(AuthUser user) {
    if (user == null || !OWNER_ROLES.contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅运营工作人员或管理员可查看数据健康", HttpStatus.FORBIDDEN);
    }
  }
}
