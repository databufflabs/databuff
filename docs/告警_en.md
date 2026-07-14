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