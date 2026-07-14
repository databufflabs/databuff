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
| Detection rules | Configuration → Alert Config → Detection Rules |
| Preset rules | Detection Rules → Preset Rules (one-click copy) |
| Convergence policy | Configuration → Alert Config → Convergence |
| Silence schedule | Configuration → Alert Config → Silence |
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

**Configuration → Alert Config → Detection Rules → New Rule**

Or copy a template from **Preset Rules** and adjust.

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

### 3. Advanced Config

| Setting | Purpose |
|---------|---------|
| **Convergence** | Merge similar alerts to reduce list noise |
| **Silence** | Suppress alert evaluation during maintenance windows |

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
| No alerts after Demo install | Manually enable a rule in Preset Rules first; install demo app for traffic; wait 1–2 evaluation cycles |
| Too many alerts | Tune thresholds; add convergence or silence policies |


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