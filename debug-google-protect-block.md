# Debug Session: google-protect-block

Status: OPEN

## Sintoma
- O Android ainda apresenta problema relacionado ao Google Play Protect / verificação de instalação.
- O usuário reporta que o bloqueio persiste.

## Hipóteses Iniciais
1. O Package Verifier / Play Protect foi reativado após reboot e voltou a bloquear instalações.
2. O bloqueio real é de permissão de instalação por fonte desconhecida, não do Play Protect.
3. O problema ocorre no fluxo de instalação pelo Agent/UI, mas não no fluxo por `adb install`.
4. Existe conflito de pacote, assinatura ou downgrade e a mensagem percebida aponta incorretamente para o Google Protect.
5. Alguma policy do sistema/device owner redireciona a instalação para um fluxo com confirmação manual.

## Plano
- Verificar no dispositivo os flags globais e seguros ligados ao verifier/Play Protect.
- Comparar instalação por `adb` com o fluxo de instalação usado pelo Agent.
- Coletar evidência de Package Installer / Package Manager antes de qualquer correção.
- Só depois aplicar a correção mínima baseada em evidência.

## Evidências
- Pendente.

## Próxima Ação
- Inspecionar o estado atual do verifier, unknown sources e Package Installer via `adb`.
