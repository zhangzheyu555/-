-- 仅本地 H2 环境使用：Java 仓储层查询用了 MySQL 的 date_format()，H2 没有此函数，
-- 用内联 Java 定义一个等价别名（只翻译代码里实际用到的 %Y %m %d %H %i %s 占位符）。
create alias if not exists date_format as '
import java.text.SimpleDateFormat;
@CODE
String dateFormat(java.sql.Timestamp ts, String pattern) {
  if (ts == null || pattern == null) return null;
  String p = pattern
      .replace("%Y", "yyyy")
      .replace("%m", "MM")
      .replace("%d", "dd")
      .replace("%H", "HH")
      .replace("%i", "mm")
      .replace("%s", "ss");
  return new SimpleDateFormat(p).format(ts);
}
';
