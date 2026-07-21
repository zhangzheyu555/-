# 产物模式: 本地已执行 npm run build (base=/admin/), 产物在 dist/ 下。
# 服务器无需 Node/npm, 仅 COPY dist 进 nginx。
FROM nginx:1.27-alpine

# 前端容器自身的 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 本地构建产物, 必须存在 frontend-vue/dist/
COPY dist/ /usr/share/nginx/html/

EXPOSE 80
