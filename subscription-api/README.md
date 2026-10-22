# GKD to AutoSkip 在线订阅转换接口

服务按需读取固定的 GKD JSON5 上游，调用 AutoSkip 仓库的 gkd2skip.py，并返回 AutoSkip JSON。成功结果在进程内缓存 5 分钟；上游暂时不可用时继续提供最后一次成功的结果。

## 接口

- GET /health：健康检查
- GET /gkd-autoskip.json：AutoSkip 在线规则订阅

部署后，把 https://你的服务域名/gkd-autoskip.json 填入 AutoSkip 的规则订阅地址。

## Render 部署

1. 将 此文件位于 autoskip 仓库的 subscription-api 子目录。
2. 在 Render 创建 Blueprint，连接仓库；若使用大仓库，在 Blueprint 文件路径中指定 subscription-api/render.yaml。
3. 部署后等待服务显示 Live，再访问 /gkd-autoskip.json 确认返回规则 JSON。

Render Blueprints 支持 Python Web 服务及 free compute plan；免费 Web 服务空闲 15 分钟后会休眠，首次访问需要等待唤醒。[Blueprint 文档](https://render.com/docs/infrastructure-as-code) · [免费服务限制](https://render.com/docs/free)

也可在支持 Python 3.11+ 的服务器运行：

    pip install -r requirements.txt
    uvicorn app:app --host 0.0.0.0 --port 8000

上游地址固定为 https://gkd667.vv.ax/gkd.json5。服务只接受 GET，不接收任意 URL，源文件最大 5 MiB，单次转换最多 120 秒。

转换器逻辑来自 https://github.com/pxlwjp0425/autoskip 。
