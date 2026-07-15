# Agent Argos — Especificação Completa do APK MDM Mupa

## Objetivo

Criar um APK independente chamado:

```text
Agent Argos
```

Pacote:

```text
com.mupa.agent.argos
```

O Agent Argos será responsável por transformar qualquer Android, TV Box, coletor, terminal de consulta e dispositivo corporativo em um dispositivo totalmente gerenciado, semelhante a:

* Urmobo
* Pulsus
* SOTI
* Scalefusion
* SureMDM
* Android Enterprise

O Agent Argos será o aplicativo principal do gerenciamento.

Ele deve funcionar como:

* Device Owner
* Administrador do dispositivo
* Launcher
* Kiosk
* Controlador
* Agente persistente
* Executor de comandos
* Watchdog
* Serviço residente

MPlayer deixa de gerenciar o dispositivo.

MPlayer apenas reproduz conteúdo.

---

# Provisionamento de novo dispositivo (passo a passo)

Esta é a sequência recomendada quando pegar um dispositivo novo e terminar com:

- Device Owner aplicado
- Launcher do Argos como Home
- Kiosk/MDM “finalizado”
- Provisionamento automático: AnyDesk + plugins + MPlayer

## Pré‑requisitos (antes de instalar o APK)

1) Faça reset/factory reset no dispositivo.
2) No primeiro boot, não adicione conta Google (Device Owner falha se existir conta).
3) Ative Opções do desenvolvedor:
   - Ative “Depuração USB” (ou “Depuração via rede”, se for o caso).
4) Conecte o dispositivo ao PC e confirme que aparece em:

```bash
adb devices
```

## Instalar o Argos

1) Instale o APK do Argos (debug/release conforme seu fluxo):

```bash
adb install -r -d caminho\para\agent-debug.apk
```

2) Abra o Argos (se o PC não puder abrir Activity diretamente, use o launcher do Android):

```bash
adb shell monkey -p com.mupa.agent.argos -c android.intent.category.LAUNCHER 1
```

## Aplicar Device Owner (crítico)

1) Execute no PC:

```bash
adb shell dpm set-device-owner com.mupa.agent.argos/.mdm.ArgosDeviceAdminReceiver
```

2) Valide:

```bash
adb shell dumpsys device_policy | grep -i "Device Owner\|com.mupa.agent.argos"
```

Observações:
- Se falhar com mensagem de “already some accounts”, volte e faça factory reset novamente.
- Em alguns dispositivos, é necessário “remover todas as contas” antes do comando acima.

## Conceder permissões (dentro do Argos)

Ao abrir o Argos, se faltar permissão crítica, ele entra automaticamente na tela “Permissões”.

1) Toque em “Conceder (guiado)” e conclua cada etapa.
2) Permissões críticas típicas:
   - Administrador do dispositivo
   - Ignorar otimização de bateria
   - Exibir sobre outros apps
   - Usage Stats
3) Para o provisionamento de apps via instalador, ative também:
   - “Instalar apps desconhecidos” para o Argos
   - “Acessibilidade” do Argos (para auto‑clicar Install/DONE no instalador)

### Fallback via ADB (UROVO / Android 13 e ROMs restritas)

Em alguns devices corporativos, as telas de permissões podem não existir, estar escondidas ou bloquear a ação no UI. Nestes casos, aplique por ADB:

```bash
# Usage Stats
adb shell appops set com.mupa.agent.argos GET_USAGE_STATS allow

# Overlay
adb shell appops set com.mupa.agent.argos SYSTEM_ALERT_WINDOW allow

# Instalar apps desconhecidos
adb shell appops set com.mupa.agent.argos REQUEST_INSTALL_PACKAGES allow

# Ignorar otimização de bateria (whitelist do Doze)
adb shell dumpsys deviceidle whitelist +com.mupa.agent.argos

# Acessibilidade (habilitar serviço do Argos)
adb shell settings put secure enabled_accessibility_services com.mupa.agent.argos/com.mupa.agent.argos.accessibility.ArgosAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### MEFERI MC45 — esconder barra de navegação (botões volume/menu/voltar)

O MC45 renderiza os botões de volume/menu/voltar numa barra de navegação própria do firmware.
Essa barra é controlada pela chave de sistema `navbar_enable`, que **só pode ser escrita por
uid shell/system/app-de-plataforma** — o SettingsProvider da MEFERI recusa a escrita de
qualquer app comum (mesmo com `WRITE_SECURE_SETTINGS`), e a API OEM `IDeviceManager.
setNavigationbarenable()` exige uid system. Portanto o agent **não consegue** alternar isso em
runtime nesta ROM; aplique no enrollment via ADB (o valor persiste após reboot):

```bash
# Esconde a barra de navegação inteira (volume + menu + voltar)
adb shell settings put system navbar_enable 0

# Para restaurar (ex.: manutenção):
adb shell settings put system navbar_enable 1
```

> O código do agent (`DeviceOwnerPolicyManager.setOemNavigationBarVisible` +
> `MeferiSdkBridge`) já faz essa chamada ao entrar/sair do lock task e passa a funcionar
> automaticamente caso o agent seja assinado com a plataforma ou instalado como app de sistema.

## Finalizar (ativar Kiosk e Launcher Argos)

1) Na tela principal (Launcher do Argos), toque no ícone de cadeado (Admin).
2) Senha padrão (primeiro acesso): `040816050223`
3) Ações:
   - “Ativar Kiosk”
   - “Ativar Launcher Argos”

Depois disso, o dispositivo fica “finalizado”: o Argos se aplica como launcher e reforça o modo gerenciado.

## Provisionamento automático (AnyDesk + plugins + MPlayer)

Quando o dispositivo está “finalizado”, o Argos executa o provisioning automaticamente pelo serviço residente:

- Instala AnyDesk
- Instala plugins do AnyDesk (lista configurada)
- Instala MPlayer
- Registra um snapshot de validação de acesso remoto (best‑effort)

Configuração via `device_config` (Firebase), exemplo:

```json
{
  "device_locked": true,
  "kiosk_enabled": true,
  "anydesk_url": "https://download.anydesk.com/anydesk.apk",
  "anydesk_plugins": [
    { "package": "com.anydesk.adcontrol.ad1", "url": "https://seu-servidor/adcontrol_ad1.apk" }
  ],
  "mplayer_url": "https://seu-servidor/mplayer_enterprise.apk"
}
```

## Validação rápida (PC)

Checar se serviço residente está ativo:

```bash
adb shell dumpsys activity services com.mupa.agent.argos
```

Checar se apps instalaram:

```bash
adb shell pm list packages | grep -i "anydesk\|mupa.player.enterprise\|anydesk.adcontrol"
```

# Arquitetura


Fluxo:

```text
Painel Argos
↓
API
↓
Firebase Wake-Up
↓
Realtime Database
↓
Agent Argos
↓
Executa
↓
Retorna status
```

IMPORTANTE:

Hoje usamos Firebase Realtime.

Manter compatibilidade.

Mas implementar nova arquitetura:

Firebase será:

* notificação
* wake-up
* sincronização

Comandos devem ficar persistidos:

API + SQLite + cache local

Para evitar perda.

---

# Comandos

Quando ocorrer:

```text
Firebase.onDataChange()
```

Agent Argos deverá:

Ler:

```json
{
"comando":"",
"package":"",
"parametros":{}
}
```

Interpretar:

```kotlin
CommandDispatcher.execute()
```

Criar:

```kotlin
sealed class DeviceCommand
```

Exemplo:

```kotlin
when(command){

OPEN_APP->{}

LOCK_TASK_ON->{}

UPDATE_APP->{}

REBOOT->{}

}
```

---

# Compatibilidade Kodular

Hoje usamos:

```text
Firebase DataChange
↓
Lê JSON
↓
Executa ação
```

Exemplo atual:

```json
{
"comando":"abrir_app",
"package":"com.mupa.player.enterprise"
}
```

ou:

```json
{
"comando":"consulta_ean",
"codbar":"789123"
}
```

ou:

```json
{
"comando":"reset_app"
}
```

ou:

```json
{
"comando":"fecha_app"
}
```

O novo Agent deve manter compatibilidade total.

---

# Comandos obrigatórios

### Aplicativos

```json
{
"comando":"abrir_app",
"package":"..."
}
```

Abrir:

```bash
monkey -p pacote 1
```

---

```json
{
"comando":"fechar_app"
}
```

Executar:

```bash
am force-stop
```

---

```json
{
"comando":"instalar_apk",
"url":"..."
}
```

Baixar APK

Instalar silenciosamente

Implementar:

PackageInstaller

ou:

```bash
pm install -r
```

---

```json
{
"comando":"desinstalar"
}
```

---

```json
{
"comando":"limpar_cache"
}
```

---

# Atualização silenciosa

Recurso obrigatório.

Fluxo:

Argos envia:

```json
{
"comando":"atualizar_app",
"package":"com.mupa.player.enterprise",
"url":"apk"
}
```

Fluxo:

Baixar APK

Validar:

* hash
* versão

Instalar

Reabrir

Retornar:

```json
{
"status":"success"
}
```

---

# Launcher Argos

Criar launcher próprio.

Mostrar somente whitelist.

Nunca listar todos aplicativos.

Whitelist:

```json
[
"com.mupa.player.enterprise",
"com.anydesk.anydeskandroid",
"com.android.settings"
]
```

Ao segurar:

mostrar:

* autostart
* persistência
* fixar
* abrir
* limpar cache
* desinstalar
* kiosk

---

# Modo Kiosk

Ativar:

```java
startLockTask()
```

Desativar:

```java
stopLockTask()
```

Implementar:

* bloquear home
* bloquear recentes
* esconder notificações
* impedir saída

---

# Lock Task

Comandos:

```json
{
"comando":"locktask_on"
}
```

```json
{
"comando":"locktask_off"
}
```

---

# Pinning

Adicionar:

Fixar aplicativo.

Comando:

```json
{
"comando":"pin_app",
"package":"..."
}
```

---

# Autostart

Permitir:

```json
{
"package":"...",
"autostart":true
}
```

Iniciar:

* boot
* reboot
* watchdog

---

# Persistência

Permitir:

```json
{
"package":"...",
"persistence":true
}
```

Se fechar:

reabrir.

---

# Recursos básicos MDM

Implementar:

### reboot

```json
{
"comando":"reboot"
}
```

---

### screenshot

```json
{
"comando":"screenshot"
}
```

---

### volume

```json
{
"comando":"volume",
"value":80
}
```

---

### brilho

```json
{
"comando":"brightness",
"value":90
}
```

---

### abrir wifi

```json
{
"comando":"wifi"
}
```

---

### abrir configurações

```json
{
"comando":"settings"
}
```

---

### mudar launcher

---

### bloquear botões físicos

* home
* volume
* recentes

---

### AnyDesk

Implementar:

Accessibility

Auto aceitar:

MediaProjection

---

### modo manutenção

---

### limpar cache

---

### fechar processos

---

### diagnóstico

Mostrar:

RAM

CPU

Bateria

WiFi

Temperatura

IP

Uptime

Armazenamento

---

# Watchdog

Implementar:

ForegroundService

WorkManager

BootReceiver

Se serviço morrer:

reiniciar

---

# Permissões

Obrigatórias:

* Device Owner
* Device Admin
* Accessibility
* Overlay
* Ignore Battery
* Usage Stats
* Notifications
* Install Unknown Apps
* Package Usage
* Query All Packages
* WakeLock

Criar:

Permission Wizard

Mostrar:

12/12 permissões concluídas

---

# Banco local

SQLite:

Tabelas:

devices

commands

logs

whitelist

autostart

watchdog

---

# Logs

Registrar:

comando recebido

executado

falhou

tempo

retorno

---

# Firebase

Escutar:

```text
devices/{serial}/comando
```

Ao alterar:

Ler JSON

Interpretar

Executar

Limpar comando após sucesso

---

Objetivo final:

Criar um APK MDM funcional, profissional e modular, capaz de executar recursos básicos das plataformas MDM tradicionais e crescer futuramente.
