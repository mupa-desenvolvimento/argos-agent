# Debug Session: argos-install-disappears

Status: OPEN

## Sintoma
- Depois de instalar a nova versão do ARGOS e reiniciar o Android, o ARGOS sumiu do dispositivo.
- Agora o usuário não consegue instalar novamente.

## Hipóteses Iniciais
1. O APK novo foi gerado com `applicationId` ou assinatura diferente da versão anterior.
2. O app não foi removido, mas ficou desabilitado, sem launcher ativo, ou crashando no boot.
3. Alguma política do sistema, DO, launcher ou package manager removeu/desativou o pacote após reinício.
4. A reinstalação está bloqueada por verificação, conflito de assinatura, downgrade ou estado inválido do instalador.
5. O APK construído não corresponde ao pacote esperado pelo dispositivo.

## Plano
- Verificar o estado real do pacote no dispositivo via `adb`.
- Confirmar `packageName`, versão e assinatura esperadas no projeto.
- Coletar mensagens do Package Manager e logs de boot/instalação.
- Só depois aplicar a correção mínima baseada em evidência.

## Hipóteses Atuais
1. O pacote não foi removido; ele foi desabilitado no Package Manager durante ou após o boot.
2. Algum fluxo de policy, receiver, launcher ou admin do próprio app está chamando disable/hide no pacote.
3. O sistema Android ou uma política externa está desabilitando o app por papel de launcher/HOME ou estado administrativo.
4. A reinstalação "não funciona" apenas porque o pacote segue presente porém desabilitado, criando percepção de remoção.
5. Existe conflito entre variantes/pacotes (`com.mupa.agent.argos` vs `com.mupa.player.enterprise`) no fluxo pós-reboot.

## Evidências
- Evidência anterior: `pm list packages` mostrou `com.mupa.agent.argos` e `com.mupa.player.enterprise` instalados.
- Evidência anterior: `pm path com.mupa.agent.argos` retornou caminho válido do APK instalado.
- Evidência anterior: `dumpsys package com.mupa.agent.argos` indicou `installed=true`, `enabled=0`, `versionCode=60`, `versionName=1.0.9`.
- Evidência anterior: reinstalação manual via `adb install -r -d ...\agent_argos-debug.apk` retornou `Success`.
- Conclusão parcial: o ARGOS não sumiu fisicamente do dispositivo; ele ficou desabilitado e precisa ter a origem dessa desativação confirmada.
- Evidência nova: `pm list packages -d` nao listou `com.mupa.agent.argos`, o que enfraquece a interpretacao de pacote desabilitado.
- Evidência nova: `dumpsys package` mostrou `hidden=false`, `suspended=false`, `stopped=false` e `enabledComponents` apenas para receivers/workers internos.
- Evidência nova: `cmd package resolve-activity` resolveu tanto `HOME` quanto `LAUNCHER` para `com.mupa.agent.argos/.ui.ArgosLauncherActivity`.
- Evidência nova: `adb shell monkey -p com.mupa.agent.argos -c android.intent.category.LAUNCHER 1` executou com sucesso.
- Evidência nova: `adb shell am start -n com.mupa.agent.argos/.ui.ArgosLauncherActivity` trouxe a task existente para frente.
- Evidência nova: `dumpsys activity activities` confirmou `ArgosLauncherActivity` em foreground/resumed.
- Evidência nova: `adb install -r -d ...\agent_argos-debug.apk` voltou a concluir com `Success`.
- Interpretação corrigida: `enabled=0` isoladamente nao prova desativacao; neste caso o pacote segue instalado, iniciavel e visivel para o sistema.

## Próxima Ação
- Diferenciar se o problema relatado ocorre na instalacao manual pelo Android/arquivo local, no fluxo de update do proprio Agent, ou apenas na percepcao apos reboot/autostart.
