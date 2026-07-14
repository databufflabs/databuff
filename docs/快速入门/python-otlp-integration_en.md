Python OTLP Integration Quick-Start Guide

This guide provides a step-by-step walkthrough for connecting a Python application to DataBuff via OpenTelemetry (OTLP) to ingest application traces and metrics.
Prerequisites

Before configuring your Python application, ensure that the core DataBuff platform components are up and running on your infrastructure.

Please refer to the Docker Deployment Guide to set up and start the DataBuff backend services.

Step 1: Install OpenTelemetry SDK & Dependencies

Install the required OpenTelemetry SDK core libraries, the OTLP exporter protocol packages, and Flask (which we will use to build a minimal target application) using pip:

pip install flask

opentelemetry-api

opentelemetry-sdk

opentelemetry-exporter-otlp

opentelemetry-instrumentation-flask
Step 2: Instrument a Minimal Python Application

Create a new file named app.py in your working directory and add the following Python snippet. This script initializes the OpenTelemetry tracer provider, routes data to the local DataBuff ingest endpoint, and auto-instruments a standard Flask web server:

from flask import Flask
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.flask import FlaskInstrumentor
1. Initialize the Tracer Provider

provider = TracerProvider()
trace.set_tracer_provider(provider)
2. Configure the OTLP Exporter to target the DataBuff gRPC ingestion port
Default DataBuff OTLP gRPC endpoint is http://localhost:4317

otlp_exporter = OTLPSpanExporter(endpoint="http://localhost:4317", insecure=True)
provider.add_span_processor(BatchSpanProcessor(otlp_exporter))
3. Create and instrument the Flask application instance

app = Flask(name)
FlaskInstrumentor().instrument_app(app)

@app.route("/")
def index():
return "DataBuff Python OTLP Integration active."

if name == "main":
app.run(port=5000)
Step 3: Execute and Generate Traffic

Run the instrumented Python application inside your terminal environment:

python app.py

Open a separate terminal prompt or use a web browser to send multiple mock HTTP requests to trigger trace collection:

curl http://localhost:5000/
Step 4: Verify Traces and Metrics in DataBuff UI

Launch your browser environment and log into the local DataBuff UI dashboard.

Navigate directly to the Trace Center or Metrics List View.

Verify that the incoming transaction streams labeled from your application framework are indexed, rendered, and mapping correctly within the active visualization grids.