# XENOWAVE 0.10.3 beta 1

## O que mudou

- **Compartilhar o XENOWAVE**: ação em Configurações → Sobre para convidar
  amigos pela folha nativa do Android, com o link público de releases.
- **Compartilhar playlists online**: a mensagem agora inclui o nome da
  playlist, o link da playlist e o link oficial do app. O destinatário pode
  abrir a playlist no XENOWAVE ou instalar o app e voltar à mensagem.
- **Recebimento de mensagens com dois links**: o app extrai apenas a URL de
  playlist válida e ignora o link de download, hosts parecidos e entradas
  malformadas.
- **Cópias portáteis de playlists locais**: exportação versionada de
  referências de músicas online em JSON, com resumo, confirmação e importação
  transacional para uma nova playlist local. Arquivos de áudio locais não são
  transferidos e são informados como excluídos.
- **Microfone**: controle explícito da solicitação em andamento para impedir
  pedidos duplicados por toques rápidos, recomposição ou retorno do sistema.
  A permissão real continua sendo consultada a cada uso; negação e revogação
  temporária são respeitadas.
- **Atualização por diferenças**: o APK novo pode ser reconstruído a partir de
  um patch XND1 verificável, com hash/tamanho exatos e fallback automático para
  o APK completo. O consentimento por versão + SHA-256 continua obrigatório.
  No par medido 72→74, o patch tem 4.745.405 bytes e economiza 67,53% do APK
  completo; a reconstrução foi conferida byte a byte.

## Atualização

A versão 0.10.2-beta.1/code72 e a 0.10.2-beta.2/code73 ainda não possuem o
aplicador de diferenças. Elas recebem esta versão pelo download completo
existente. O suporte delta passa a valer para uma versão posterior que já
contenha este código.

## Limitações

- Ainda não há perfis, seguidores, feed ou mural de comunidade; esta etapa
  cobre convites e troca de playlists.
- A importação portátil cria uma nova playlist local e não envia nada
  automaticamente à conta remota.
- A validação em aparelho real do microfone, do compartilhamento e do fluxo
  delta ainda depende de um aparelho com a versão instalada.
