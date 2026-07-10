package com.storeprofit.system.staticresources;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FrontendStoragePolicyTest {
  @Test
  void frontendOnlyPersistsRealBusinessKeysToMysqlStorage() throws IOException {
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(databaseJs)
        .contains("const MYSQL_STORAGE_KEYS")
        .contains("async function migrateBrowserDataToMysql")
        .contains("localStorage.removeItem(STORAGE_PREFIX+k)");

    assertThat(databaseJs)
        .doesNotContain("localStorage.setItem(STORAGE_PREFIX+k,value)")
        .doesNotContain("localStorage.setItem")
        .doesNotContain("const raw=localStorage.getItem(STORAGE_PREFIX+k);\r\n    return raw==null?null:JSON.parse(raw);")
        .doesNotContain("const raw=localStorage.getItem(STORAGE_PREFIX+k);\n    return raw==null?null:JSON.parse(raw);")
        .doesNotContain("window.storage&&typeof window.storage.set");

    assertThat(indexHtml)
        .contains("migrateBrowserDataToMysql()")
        .contains("if(!st){st=seedStores();}")
        .doesNotContain("PIN=await sGet(\"app_pin\");")
        .doesNotContain("if(!st){st=seedStores();await sSet(\"stores\",st);}")
        .doesNotContain("await sSet(\"accounts\",ACCOUNTS);");
  }

  @Test
  void frontendUsesStoreIdAsStoreManagerLoginPassword() throws IOException {
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(databaseJs)
        .contains("function storeLoginCode")
        .contains("const pass=storeLoginCode(s)")
        .contains("if(role===\"店长\")return (typeof MANAGED_SID===\"string\"&&MANAGED_SID)||\"\";");

    assertThat(databaseJs)
        .doesNotContain("pass:(s.code||s.id)");
  }

  @Test
  void frontendUsesSongtiAsGlobalFont() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("body{font-family:'SimSun','宋体'")
        .contains("button,input,select,textarea{font-family:inherit")
        .doesNotContain("'Noto Sans SC'");
  }

  @Test
  void frontendWritesOperationLogsToBackendAuditApiInsteadOfBrowserStorage() throws IOException {
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(databaseJs)
        .contains("const AUDIT_WRITE_ENDPOINT=\"/api/audit/logs\"")
        .contains("async function postAuditLog")
        .contains("await postAuditLog")
        .contains("beforeJson")
        .contains("afterJson")
        .doesNotContain("sSet(\"logs\",LOGS)")
        .doesNotContain("LOGS.unshift");
  }

  @Test
  void dataEntrySaveAndDeleteUseStructuredFinanceApiInsteadOfEntriesStorageWrite() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("const FINANCE_ENTRY_ENDPOINT=\"/api/finance/entries\"")
        .contains("function financeEntryPayload")
        .contains("async function saveProfitEntryToBackend")
        .contains("async function deleteProfitEntryFromBackend")
        .contains("await saveProfitEntryToBackend(sid,m,e)")
        .contains("await deleteProfitEntryFromBackend(sid,m)")
        .doesNotContain("ENTRIES[eKey(sid,m)]=e;await sSet(\"entries\",ENTRIES);")
        .doesNotContain("delete ENTRIES[key]; await sSet(\"entries\",ENTRIES);");
  }

  @Test
  void dataHealthNavigationBelongsToOperationsStaffInsteadOfBoss() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(databaseJs)
        .contains("function canViewDataHealth")
        .contains("return role===\"\u8fd0\u8425\";")
        .contains("\"老板\":[\"dataHealth\"")
        .contains("function roleHiddenTabs")
        .contains("if(canViewDataHealth(role))return hidden.filter(tab=>tab!==\"dataHealth\");")
        .contains("const hidden=roleHiddenTabs(CURRENT_ROLE)");

    assertThat(indexHtml)
        .contains("if(!canViewDataHealth())")
        .doesNotContain("if(!isBossRole()){\r\n    body.innerHTML=`<div class=\"card\"><div class=\"empty\"><b>无权查看数据健康</b>该入口只给老板/管理员使用。</div></div>`;")
        .doesNotContain("if(!isBossRole()){\n    body.innerHTML=`<div class=\"card\"><div class=\"empty\"><b>无权查看数据健康</b>该入口只给老板/管理员使用。</div></div>`;");
  }

  @Test
  void roleNavigationHidesDuplicateAndBlankTabs() throws IOException {
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(databaseJs)
        .contains("function normalizeRoleNavigation")
        .contains("const seen=new Set()")
        .contains("!key||!label||seen.has(key)")
        .contains("normalizeRoleNavigation(hidden)");
  }
}
