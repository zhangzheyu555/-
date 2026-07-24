-- 修复历史视频恢复过程中将 UTF-8“设备培训”按 Windows-1252 解码后写入的分类乱码。
-- 仅匹配已确认的异常值，避免对用户自定义分类做推测性转码。
update training_video
set category = convert(0xE8AEBEE5A487E59FB9E8AEAD using utf8mb4)
where cast(category as binary) =
      0xC3A8C2AEC2BEC3A5C2A4E280A1C3A5C5B8C2B9C3A8C2AEC2AD;
