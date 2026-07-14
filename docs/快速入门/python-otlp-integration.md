Python OTLP 集成快速入门指南

本指南将逐步介绍如何通过 OpenTelemetry (OTLP) 将 Python 应用程序连接到 DataBuff，以实现应用链路追踪 (Traces) 和指标 (Metrics) 的数据采集。
前置条件

在配置 Python 应用程序之前，请确保 DataBuff Platform 的核心组件已在您的基础设施中正常运行。

请参考 Docker 安装部署指南 完成 DataBuff 后端服务的启动与配置。

步骤 1：安装 OpenTelemetry SDK 与依赖项

使用 pip 安装所需的 OpenTelemetry SDK 核心库、OTLP 导出器协议包以及用于构建示例应用的 Flask 框架：

pip install flask

opentelemetry-api

opentelemetry-sdk

opentelemetry-exporter-otlp

opentelemetry-instrumentation-flask
步骤 2：构建基础 Python 应用程序

在您的工作目录中创建一个名为 app.py 的文件，并添加以下 Python 代码。该脚本将初始化 OpenTelemetry 追踪提供者 (Tracer Provider)，将数据路由至本地 DataBuff 接收端，并自动对 Flask Web 服务器进行埋点监控：

from flask import Flask
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.flask import FlaskInstrumentor
1. 初始化 Tracer Provider

provider = TracerProvider()
trace.set_tracer_provider(provider)
2. 配置 OTLP Exporter 指向 DataBuff gRPC 数据接收端口
DataBuff 默认的 OTLP gRPC 端口为 http://localhost:4317

otlp_exporter = OTLPSpanExporter(endpoint="http://localhost:4317", insecure=True)
provider.add_span_processor(BatchSpanProcessor(otlp_exporter))
3. 创建并对 Flask 应用实例进行自动埋点 (Instrumentation)

app = Flask(name)
FlaskInstrumentor().instrument_app(app)

@app.route("/")
def index():
return "DataBuff Python OTLP Integration active."

if name == "main":
app.run(port=5000)
步骤 3：运行应用并生成测试流量

在终端环境中运行配置好的 Python 应用程序：

python app.py

打开另一个终端窗口或使用浏览器，发送多次模拟 HTTP 请求以触发链路数据采集：

curl http://localhost:5000/
步骤 4：在 DataBuff UI 中验证数据

打开浏览器并登录本地的 DataBuff UI 控制台。

直接导航至 Trace Center (链路中心) 或 Metrics List View (指标列表视图)。

验证来自 Python 应用的事务流数据是否已被成功索引、渲染并在活跃的可视化网格中正确映射。