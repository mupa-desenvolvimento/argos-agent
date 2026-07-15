# Protocolo de Comandos ARGOS (Web ⇄ Firebase RTDB ⇄ Agent)

Este documento define o protocolo canônico de comandos do ARGOS: como o **Web ARGOS** publica comandos no **Firebase Realtime Database (RTDB)** e como o **ARGOS Agent** consome, executa e publica ACKs, estados, resultado, histórico e logs.

O objetivo é garantir confiabilidade, consistência, rastreabilidade, execução offline, idempotência e execução em massa (grupo) sem perda de comandos.

## 1) Caminhos canônicos (RTDB)

Todos os dados de device são centralizados em:

`m_argos/devices/{device_id}`

### 1.1) Nós relevantes

- `m_argos/devices/{device_id}/command_queue/{command_id}`
  - Fila canônica de comandos pendentes/ativos por dispositivo.
- `m_argos/devices/{device_id}/command_history/{command_id}`
  - Histórico canônico do comando (snapshot do envelope + timeline).
- `m_argos/devices/{device_id}/command_history/{command_id}/timeline/{event_id}`
  - Timeline de eventos (queued/received/executing/success/failed/retry…).
- `m_argos/devices/{device_id}/command_status/*`
  - Estado atual resumido para UI (último comando).
- `m_argos/devices/{device_id}/commands`
  - Espelho legado (compatibilidade).
- `m_argos/devices/{device_id}/command_result/*`
  - Resultado resumido (compatibilidade / UI rápida).
- `m_argos/devices/{device_id}/logs/{log_id}`
  - Logs do dispositivo. Para comandos: `category = "COMMANDS"`.

### 1.2) Execução em massa (grupo)

- `argos_group_commands/{group_command_id}`
  - Registro do envio em massa (metadados + payload + device_ids).
- `argos_group_commands/{group_command_id}/timeline/{event_id}`
  - Timeline do comando de grupo (criação, fan-out, etc).
- `argos_group_commands/{group_command_id}/device_commands/{device_id}`
  - Sub-tarefa por dispositivo: status/result/message/retry/timeline.

## 2) Envelope de comando (DeviceCommandEnvelope)

O comando canônico é um JSON publicado no nó:

`m_argos/devices/{device_id}/command_queue/{command_id}`

O mesmo envelope é replicado em:

`m_argos/devices/{device_id}/command_history/{command_id}`

### 2.1) Campos mínimos obrigatórios

- `command_id` (string): identificador único (idempotência).
- `command` (string): nome canônico do comando.
- `timestamp` (number): epoch ms (criação).
- `device_id` (string): alvo do comando.
- `created_by` (string | null): ator (email/identificador).
- `priority` ("low" | "normal" | "high" | "critical")
- `status` (ver seção 3)
- `params` (object): parâmetros do comando.
- `command_hash` (string): hash estável de `command + params` (idempotência adicional).
- `execution_token` (string): token de execução (ex: `{command_id}:{command_hash}`).
- `ack_required` (boolean): se o Agent deve emitir ACKs por estado (true).
- `retry_count` (number): tentativas já realizadas.
- `max_retries` (number): máximo de tentativas.
- `timeout_ms` (number): limite de execução (ms) para detectar travas.
- `created_at` (number): epoch ms.
- `updated_at` (number): epoch ms.

### 2.2) Campos de rastreabilidade e controle

- `type` (string): categoria/tipo lógico (default = `command`).
- `result` (string | null): resultado lógico (ex: `"success"`, `"failed"`, `"delegated"`).
- `message` (string | null): mensagem humana/máquina.
- `received_at` (number | null)
- `started_at` (number | null)
- `finished_at` (number | null)
- `dispatched_at` (number): quando foi publicado no RTDB.
- `expires_at` (number | null): TTL absoluto (epoch ms). Se expirado, não executar.
- `next_retry_at` (number | null): quando a próxima tentativa pode ocorrer.
- `cancel_requested_at` (number | null): solicitação de cancelamento.

### 2.3) Campos de escopo (grupo)

- `scope` ("device" | "group")
- `group_command_id` (string | null)
- `group_id` (string | null)
- `group_name` (string | null)
- `parent_command_id` (string | null)

### 2.4) Compatibilidade legado

- `legacy_alias` (string): alias legado para `commands.comando` (ex: `open_app` → `abrir_app`).
- `transport` ("queue" | "queue_mirror")

### 2.5) Regras de compatibilidade RTDB

- O RTDB não aceita `undefined`. O Web deve remover chaves com `undefined`.
- Campos opcionais ausentes devem ser omitidos ou `null` (não `undefined`).

## 3) Máquina de estados (status)

Estados padronizados:

- `queued`
- `dispatched`
- `received`
- `executing`
- `success`
- `partial_success`
- `failed`
- `cancelled`
- `expired`
- `retrying`
- `rollback`
- `timeout`

### 3.1) Transições esperadas

- Publicação (Web):
  - `queued` → (timeline: `queued_in_web`)
  - `dispatched` → (timeline: `written_to_rtdb`)
- Consumo (Agent):
  - `received` quando o Agent decide assumir o comando
  - `executing` ao iniciar execução
  - `success` | `partial_success` | `failed` ao finalizar
- Controle:
  - `expired` se `expires_at` passou antes do `received`
  - `cancelled` se cancelado antes da execução
  - `timeout` se `executing` ultrapassa `timeout_ms`
  - `retrying` ao agendar nova tentativa (`retry_count < max_retries`)

## 4) Idempotência (não executar duas vezes)

O Agent deve tratar `command_queue` como canônico e garantir idempotência:

- A chave primária é `command_id`.
- `command_hash` e `execution_token` reforçam a idempotência quando houver replay.
- O Agent deve evitar executar novamente comandos já terminalizados (`success`, `failed`, `cancelled`, `expired`, `timeout`, `rollback`, `partial_success`) e comandos com `executed = true`.

## 5) Offline, TTL e cancelamento

- Offline: comandos permanecem em `command_queue` e são processados quando o Agent reconectar.
- TTL: se `expires_at` existir e `now > expires_at`, o Agent marca `expired` e não executa.
- Cancelamento: se `status == "cancelled"` ou `cancel_requested_at` existir e o comando ainda não executou, o Agent deve finalizar como `cancelled`.

## 6) Retry automático (backoff)

Retry é controlado por:

- `retry_count`
- `max_retries`
- `next_retry_at`

Regras:

- Em falha/timeout e `retry_count < max_retries`, o Agent:
  - marca `retrying`
  - incrementa `retry_count`
  - calcula `next_retry_at` (backoff exponencial)
  - reprocessa após `next_retry_at`

## 7) Timeout e auto-healing

- `timeout_ms` define o limite para comando em `executing`.
- Se `status == "executing"` e `now - updated_at > timeout_ms`, o Agent marca `timeout` e aplica a política de retry (se habilitada).

## 8) Lock de execução (concorrência)

O Agent deve evitar concorrência perigosa. Locks sugeridos por categoria:

- `app_package_runtime`: installs/uninstalls/updates
- `device_lifecycle`: reboot/factory reset
- `policy_runtime`: kiosk/whitelist/policies

Se um lock já estiver ocupado por outro `command_id`, o comando deve permanecer `queued` com mensagem `waiting_lock:{lockKey}` e retry curto.

## 9) ACK e escrita de retorno (Agent → RTDB)

Para cada transição importante, o Agent atualiza:

- `command_queue/{command_id}` (status/result/message/retry/timestamps)
- `command_history/{command_id}` (espelhado + `timeline`)
- `command_status/*` (resumo do atual)
- `command_result/*` (resumo compatível)
- `commands/*` (espelho compatível)
- `logs/*` com `category="COMMANDS"` e níveis:
  - `INFO`, `WARNING`, `ERROR`, `CRITICAL`

Quando o comando pertence a grupo (`group_command_id`):

- `argos_group_commands/{group_command_id}/device_commands/{device_id}` é atualizado com status/result/message/retry e timeline.

## 10) Comandos suportados (catálogo)

Os nomes abaixo representam o valor de `command`.

### 10.1) Sistema / Lifecycle

- `reboot_device` (aliases aceitos pelo Agent: `reiniciar`, `reboot`, `restart_device`)
  - params: `{ }`
- `factory_reset_keep_config` (UI crítica)
  - params: `{ }`
- Observação: hoje `factory_reset_keep_config` executa o mesmo fluxo de `factory_reset` (não há “manter config” ainda).
- `factory_reset` / `wipe_device` / `reset_factory` / `reset_fabrica` / `resetar_fabrica` (aliases aceitos pelo Agent)
  - params: `{ }`

### 10.2) Aplicativos

- `install_apk` / `install_app` / `update_app`
  - params típicos:
    - `url` (string)
    - `package` (string, opcional)
    - `version` (string, opcional)
    - `force` (boolean, opcional)
    - `silent` (boolean, opcional)
    - `auto_open` (boolean, opcional)
    - `checksum` (string sha256, opcional)
- `uninstall_app` / `remove_app` / `delete_app`
  - params:
    - `package` (string)
    - `silent` (boolean, opcional)
    - `force` (boolean, opcional)
    - `timeout_ms` (number, opcional)
- `open_app` (alias legado: `abrir_app`)
  - params: `{ package: string }`
- `close_app` (alias legado: `fechar_app`)
  - params: `{ package: string }`

### 10.3) Kiosk / Políticas

- `kiosk_on` / `locktask_on`
  - params: `{ }`
- `kiosk_off` / `locktask_off` / `kiosk_disable`
  - params: `{ }`
- `set_whitelist` / `set_allowed_apps` / `set_kiosk_apps`
  - params: `{ whitelist: string[] }`
- `open_settings` (aliases aceitos pelo Agent: `abrir_configuracoes`, `abrir_config`)
  - params: `{ }`

### 10.4) Telemetria / Utilitários

- `sincronizar` (alias: `sync_now`, `report_now`, etc)
  - força push imediato de `info/telemetry/location/installed_apps`
- `screenshot` / `take_screenshot` / `capturar_tela`
  - params: `{ }`

### 10.5) Configurações do dispositivo

- `volume`
  - params: `{ value: number(0..100) }`
- `brightness`
  - params: `{ value: number(0..100) }` (requer device owner)
- `keep_awake`
  - params: `{ enabled?: boolean, value?: boolean }`
- `awake_schedule` / `set_awake_schedule`
  - params: `{ enabled?: boolean, start_min?: number, end_min?: number, start?: string, end?: string }`
- `rest_mode_on` / `rest_on` / `sleep_on` / `rest` / `sleep` (aliases aceitos pelo Agent)
  - params: `{ minutes?: number, until_ms?: number, lock_now?: boolean }`
- `rest_mode_off` / `rest_off` / `wake_mode` / `wake_up` (aliases aceitos pelo Agent)
  - params: `{ }`
- `rest_screen_timeout`
  - params: `{ ms?: number, value_ms?: number }`

### 10.6) Suporte remoto (AnyDesk)

- `anydesk_accept` / `anydesk_start_now` / `remote_support_accept` / `remote_support_request`
  - params: `{ duration_ms?: number, tap_x?: number, tap_y?: number, tap_x_pct?: number, tap_y_pct?: number }`

### 10.7) Reservados / não implementados (responderá `failed`)

- `maintenance_on` / `maintenance_off`
- `clear_cache`

## 11) Exemplos

### 11.1) Exemplo: comando por dispositivo (install_apk)

Path:

`m_argos/devices/DEVICE123/command_queue/cmd-install_apk-...`

Payload (exemplo):

```json
{
  "command_id": "cmd-install_apk-1710000000000-abc123",
  "command": "install_apk",
  "timestamp": 1710000000000,
  "device_id": "DEVICE123",
  "created_by": "user@empresa.com",
  "type": "install_apk",
  "priority": "high",
  "command_hash": "h4a1f2c9d",
  "execution_token": "cmd-install_apk-1710000000000-abc123:h4a1f2c9d",
  "ack_required": true,
  "status": "queued",
  "result": null,
  "message": null,
  "received_at": null,
  "started_at": null,
  "finished_at": null,
  "retry_count": 0,
  "max_retries": 3,
  "timeout_ms": 300000,
  "created_at": 1710000000000,
  "updated_at": 1710000000000,
  "dispatched_at": 1710000000000,
  "expires_at": 1710086400000,
  "next_retry_at": null,
  "cancel_requested_at": null,
  "scope": "device",
  "parent_command_id": null,
  "group_command_id": null,
  "group_id": null,
  "group_name": null,
  "transport": "queue",
  "legacy_alias": "install_apk",
  "params": {
    "url": "https://.../app.apk",
    "package": "com.exemplo.app",
    "silent": true,
    "force": true
  }
}
```

### 11.2) Exemplo: fan-out de grupo

- Web cria `argos_group_commands/{group_command_id}`
- Para cada device:
  - escreve `m_argos/devices/{device_id}/command_queue/{command_id}`
  - escreve `argos_group_commands/{group_command_id}/device_commands/{device_id}`
- O Agent, ao executar, atualiza `argos_group_commands/{group_command_id}/device_commands/{device_id}` com status/result/message.

## 12) Compatibilidade com o nó legado `commands`

O Web pode espelhar o envelope em:

`m_argos/devices/{device_id}/commands`

Este espelho existe para:

- compatibilidade com versões antigas do Agent
- telas legadas que ainda leem `commands` diretamente

Regra: o **canônico** é sempre `command_queue` + `command_history`. O nó `commands` é somente espelho.

## 13) Implementação referência (código)

- Web (publicação/envelope):
  - [prepareDeviceCommandWrites](file:///c:/src/argus-device-hub/src/lib/argos-devices.ts)
  - [sendGroupCommand](file:///c:/src/argus-device-hub/src/lib/argos-groups.ts)
- Agent (consumo/ACK/locks/retry/timeout):
  - [RealtimeCommandManager](file:///c:/src/mupa_player_enterprise/agent/src/main/java/com/mupa/agent/argos/commands/RealtimeCommandManager.kt)
  - [CommandDispatcher](file:///c:/src/mupa_player_enterprise/agent/src/main/java/com/mupa/agent/argos/commands/CommandDispatcher.kt)
