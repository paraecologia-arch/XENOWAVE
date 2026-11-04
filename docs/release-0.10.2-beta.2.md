# XENOWAVE 0.10.2 beta 2

## O que mudou

- **Atualização com confirmação**: o XENOWAVE continua verificando versões
  oficiais automaticamente ao abrir o app e em segundo plano, mas agora mostra
  **Atualizar** e **Agora não** antes de baixar ou instalar qualquer coisa.
- **Agora não** não cria download nem sessão de instalação. O aviso não volta a
  cada troca de tela ou retomada do foco na mesma sessão.
- **Atualizar** autoriza somente a versão exibida, vinculada a `versionCode`,
  SHA-256 e ao pacote `com.dd3boh.xenowave`. Uma versão posterior não herda
  essa autorização.
- O progresso aparece no diálogo, em **Configurações → Atualizações** e na
  notificação quando ela está disponível. Falhas de rede, espaço ou permissão
  do Android são explicadas com uma ação de nova tentativa.
- Se houver música tocando, a instalação aguarda e informa isso; o player não é
  interrompido.
- A tela de configurações foi ajustada para deixar claro que a verificação é
  automática, mas a instalação só começa depois de tocar em **Atualizar**.

## Instalação

Esta versão ainda é instalada manualmente por cima da versão anterior. Depois
de instalada, ela é o primeiro cliente que exercita o novo fluxo de confirmação
quando uma versão posterior for publicada. O Android pode pedir a confirmação
de instalação dele.

## Limitações desta versão

- A atualização por diferenças ainda não está implementada; esta versão usa o
  APK completo.
- O compartilhamento comunitário e a correção da permissão repetida do
  microfone não fazem parte desta alteração.
- A validação em aparelho real do novo aviso e do fluxo de confirmação ainda
  precisa ser feita com uma versão publicada posteriormente.
- A sincronização de playlists permanece como na versão anterior.
