# Página pública de download

## Objetivo

O convite compartilhado abre `https://paraecologia-arch.github.io/XENOWAVE/`,
uma página leve que pergunta se a pessoa deseja baixar o XENOWAVE e leva
diretamente ao APK oficial. O visitante não precisa entender GitHub Releases,
escolher entre APK, patch ou checksum.

## Fonte e publicação

- Origem: `website/`.
- Publicação: `.github/workflows/pages.yml`, que envia `website/` como artefato
  do GitHub Pages a cada push na branch de desenvolvimento.
- O endereço usa o base path `/XENOWAVE/`; por isso todos os links da página
  são relativos.
- Nenhum download começa ao abrir a página. O botão é um link HTTPS normal,
  acionado pela pessoa. O Android controla download, instalação e autorizações.

## Manifesto `download.json`

O manifesto é separado do `update.json` do atualizador:

- `download.json` descreve o APK para a página humana (versão, `versionCode`,
  `minSdk`, tamanho, SHA-256 e URL HTTPS).
- `update.json` é o contrato do cliente Android e deve ser publicado como asset
  da release, ao lado do APK.
- Ambos são gerados a partir do **mesmo APK final assinado**, depois que a
  release e o download público forem verificados.

A página valida o manifesto no navegador: exige HTTPS, host `github.com`,
caminho de release deste repositório, nome `*-core.apk` igual ao da URL, hash
SHA-256, tamanho plausível e `versionCode` numérico. URL com query string,
fragmento, host estrangeiro, caminho alternativo ou patch é recusada.

Se o manifesto falhar, a página mostra erro e usa um link de fallback de uma
versão pública real e verificada. O fallback não é chamado de "mais recente".

## Checklist de nova publicação

1. Publicar e verificar o APK assinado na release.
2. Gerar `update.json`, `SHA256SUMS.txt` e o patch da versão anterior.
3. Fazer download anônimo do APK e conferir tamanho e SHA-256.
4. Atualizar `website/download.json` com os dados verificados.
5. Atualizar o fallback em `website/app.js` para a mesma versão.
6. Atualizar o link do `<noscript>` em `website/index.html`.
7. Rodar `npm test` em `website/`.
8. Fazer commit/push e confirmar o deploy do GitHub Pages.
9. Abrir a página sem login, confirmar HTTP 200, manifesto válido e botão
   apontando para o APK novo.

O GitHub Pages pode manter cache de alguns minutos. O `fetch` usa
`cache: "no-store"`; ainda assim, uma atualização recém-publicada pode demorar
um pouco para aparecer em todos os dispositivos.
