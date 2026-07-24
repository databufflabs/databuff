<p align="center">
  <a href="告警.md">中文</a>
  &nbsp;|&nbsp;
  <a href="告警_en.md">English</a>
</p>

# User Guide · Alerting

Automatically record and fire alerts when metrics go wrong.

---

## Capabilities

| Capability | Description |
|------------|-------------|
| **Threshold alerts** | Fire when error rate, latency, throughput, etc. cross the line |
| **Change detection** | Catch sudden metric shifts |
| **Scheduled evaluation** | Runs every minute, looking back 5 minutes |
| **Event records** | Track trigger, recovery, handling (auto-resolved when metrics recover) |
| **AI analysis** | Ask for root cause directly from alert details |

Evaluation mechanics: [Architecture · Alerting](../架构设计/告警_en.md).

---

## Menu Paths

| Feature | Path |
|---------|------|
| Detection rules | Configuration → Alert Config |
| Alert list | Alert Center → Alert List |

---

## Workflow

```mermaid
flowchart LR
  A["Create rule"] --> B["Scheduled eval"]
  B --> C["Alert fires"]
  C --> D["Alert Center"]
  D --> E["AI analysis"]
```

### 1. Create a Detection Rule

**Configuration → Alert Config → New Rule**

Configurable fields:

- **Scope**: service or instance
- **Metrics**: error rate, avg latency, P99 latency, request count, etc.
- **Condition**: threshold (above/below) or change detection
- **Severity**: Info / Warning / Critical
- **Evaluation**: follows platform default (every minute)

### 2. View and Handle Alerts

**Alert Center → Alert List**

Filter by service, severity, status. Click an alert for details:

- Abnormal metric trends
- Related traces and logs
- (Optional) AI root cause analysis, or **Alert Center → Manual Root Cause Analysis** for a time-range investigation
- Handling log

Alerts auto-resolve when metrics recover.

---

## Configuration Example

The following example configures a critical alert when the average error rate of `order-service` exceeds 5% over the most recent 5-minute window.

### Create the rule

Go to **Configuration → Alert Config → New Rule** and enter the following values:

| Field | Example | Description |
|-------|---------|-------------|
| Rule name | `order-service error rate too high` | Identifies the rule in the rule list and event records |
| Status | Enabled | Only enabled rules are evaluated |
| Metric | `service.error.pct` | Service entry error rate, expressed as a percentage |
| Aggregation | Average | Averages the error rate over the evaluation window |
| Evaluation window | 5 minutes | Stored as `period: 300` seconds and evaluated against the latest 5 minutes |
| Comparison | Greater than `>` | Triggers when the metric is strictly above the threshold |
| Critical threshold | `5%` | Fires a critical alert when the error rate exceeds 5% |
| Scope | `service = order-service` | Limits the rule to the selected service |

The equivalent JSON is shown below. In normal use, fill in the form and let the page generate and save this rule data:

```json
{
  "classification": "singleMetric",
  "ruleName": "order-service error rate too high",
  "enabled": true,
  "query": {
    "1": {
      "way": "threshold",
      "period": 300,
      "unit": "%",
      "view_unit": "%",
      "_scale": 1,
      "time_aggregator": "avg",
      "comparison": ">",
      "thresholds": {
        "critical": 5,
        "warning": null
      },
      "A": {
        "metric": "service.error.pct",
        "aggs": "avg",
        "by": ["service"],
        "from": [
          {
            "connector": "AND",
            "left": "service",
            "operator": "=",
            "right": "order-service"
          }
        ]
      }
    }
  }
}
```

Notes:

- `period: 300` means 300 seconds, or 5 minutes. The current implementation uses this as the metric lookback window.
- `service.error.pct` returns a percentage, so enter `5` as the threshold rather than `0.05`.
- `by: ["service"]` groups results by service. To monitor every service, remove the service filter from `from`.
- This example means “the average error rate in the most recent 5-minute window is above 5%”; it does not require all five individual samples to exceed the threshold.

### What happens when it fires

The system evaluates the rule every minute. When the condition is met:

- The alert appears in **Alert Center → Alert List**;
- The event record includes the rule name, service, severity, trigger status, and alert description;
- Alert details show the abnormal metric trend and can be correlated with traces, logs, and AI analysis;
- Once the metric returns below the threshold, the alert is automatically marked as resolved.

---

## Working with AI

From alert details or AI Platform:

> "order-service error rate alert — help me analyze the cause"

AI queries metrics, traces, and topology automatically. For Agent integration, use MCP tool `queryServiceAlarms` — see [Agent Integration](Agent集成_en.md).

---

## FAQ

| Symptom | Action |
|---------|--------|
| No alerts after creating rules | Ensure services have metrics; evaluation runs every minute; verify rule scope (see [Docker](../运维参考/Docker运维_en.md#common-issues) / [K8s](../运维参考/K8s运维_en.md#common-issues) ops troubleshooting) |
| No alerts after Demo install | Create and enable a detection rule first; install demo app for traffic; wait 1–2 evaluation cycles |
| Too many alerts | Tune thresholds or narrow the monitoring scope |


# Alert Notification Channel (Webhook)

DataBuff provides a Webhook notification channel to push real-time system alerts and alarms to your custom HTTP endpoints. When an alarm event is triggered, the system automatically sends an HTTP POST request containing alert details in JSON format.

## Configuration Parameters

To set up the alert notification channel, the following parameters are used within the configuration map:

| Parameter Key | Type    | Description | Default |
| :--- | :--- | :--- | :--- |
| `webhookUrl`  | String  | The target HTTP/HTTPS URL where the JSON alert payload will be sent. | `""` (Empty) |
| `enabled`     | Boolean | Toggles the notification channel system. Set to `true` to activate. | `false` |

## Webhook Payload Format

The notification system transmits payloads with the header `Content-Type: application/json`. The timeout for connection is configured at 3 seconds, with a maximum request timeout of 5 seconds.

### Supported Properties

The JSON payload contains the following alert fields:
* **`alarmId`** (String): The unique identifier of the triggered alarm.
* **`service`** (String): The name of the service or component generating the alert.
* **`status`** (String): The current state of the alarm (e.g., triggered, resolved).
* **`message`** (String): A detailed text description of the alarm event.

### JSON Example

Below is an example of the JSON payload sent by the DataBuff system to your webhook endpoint:

```json
{
  "alarmId": "ALARM-2026-0091",
  "service": "auth-service-vm",
  "status": "CRITICAL",
  "message": "Memory usage exceeded 92% on instance node-01."
}
```
