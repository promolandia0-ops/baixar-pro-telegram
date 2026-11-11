# Baixar pro Telegram

App Android: cole o link de um site → o app percorre o site (mesmo domínio, profundidade/limite configuráveis) → lista todos os vídeos (tags `<video>`/`<source>`, links .mp4/.webm/.mov/.mkv/.m3u8, players KVS, embeds YouTube/Vimeo/etc. via yt-dlp no servidor `baixar-video-ezck.onrender.com`) → copia os links → baixa e envia cada um para as **Mensagens Salvas** do Telegram (TDLib, login com seu api_id/api_hash).

Build: `./gradlew assembleRelease` (keystore em `keystore/`, fora do git).

## 1.6.5
- Corrige o crash ao abrir o Navegador (globo): o WebView passou a ser criado com o contexto da tela, não com o da aplicação.
- Busca em 4K: a lista mostra os resultados da busca mesmo quando a sonda de resolução falha. Filtro padrão “Qualquer”; resolução desconhecida não some. Erros reais (servidor ocupado, etc.) aparecem na tela, com nova tentativa se o servidor estiver acordando.

## 1.6.4
- Nova aba **Buscar 4K**: busca por texto (tema, nome ou título). Filtros 4K / 1080p+ / Qualquer, lista com miniatura, duração e resolução, prévia muda (se o site oferecer um clipe), rolagem infinita. Toque baixa a melhor qualidade (vídeo+áudio) e envia ao Telegram. Também dá para colar um link e achar o mesmo vídeo em resolução maior.
- Navegador mais rápido, com bloqueador de anúncios e rastreadores (listas no estilo EasyList + EasyPrivacy, CSS cosmético, sem janelas pop-up). O histórico do WebView permanece sem recarregar a página ao voltar.
- Login que você faz no navegador do app (YouTube etc.) permanece neste aparelho (CookieManager + perfil do WebView). Varrer tudo, busca por nome, busca em 4K e o download reutilizam esses cookies e o mesmo User-Agent. O servidor yt-dlp recebe os cookies só na requisição, não guarda sessão.

## 1.6.3
- Varrer tudo: campo do site + campo Nome + botão Buscar. A busca usa a pesquisa do próprio site (form/OpenSearch ou `/search?q=`, `/?s=`), a busca do YouTube (canal ou ytsearch) pelo servidor, ou só segue links cujo texto/endereço batem com o nome.
- A lista de resultados mostra miniatura e título; você escolhe os vídeos ou toca em “Baixar todos”. O download em 8 conexões continua igual.
- “Varrer o site inteiro” segue disponível, sem o filtro de nome.

## 1.6.2
- Varredura mais forte: `<video>`/`<source>`, `og:video`, JSON-LD, config de player em `<script>`, links diretos `.mp4`/`.m3u8`/`.webm`, iframes/embeds (um nível) e fallback no servidor yt-dlp quando a página não mostra o arquivo.
- Se o servidor não consegue ler o link (YouTube pedindo login, X, etc.), a mensagem de erro aparece na tela — não só “Nenhum vídeo encontrado”.
- Varrer tudo: campo “Filtrar por nome” (sem acento/maiúsculas). Só entra na fila e só baixa o que bater no título, nome do arquivo, alt, texto do link ou texto ao redor.
- Download mais rápido: arquivos grandes em até 8 conexões em paralelo (HTTP Range), com retomada se a conexão cair. Sem Range, baixa numa conexão só (como antes). HLS já era paralelo.
- MEGA segue igual (cliente próprio, fora da varredura HTML).

## 1.5.0
- Novo visual Material 3 com menu inferior: Início, Varrer tudo, Filtrar, Fila, Histórico + Configurações.
- Início: vários links de uma vez (um por linha) e botão Baixar.
- Varrer tudo: varre o site (mesmo domínio, limite de páginas/profundidade) e baixa tudo automaticamente.
- Filtrar: só os vídeos de uma página, listados com tamanho e já marcados.
- Fila com progresso/status, Histórico com Reenviar, tamanho antes de baixar, Testar conexão, qualidade e envio automático.

## 1.6.1
- Correção do download de links públicos do MEGA (Início, Varrer tudo e Filtrar): a busca não esconde mais o erro real atrás de “Nenhum vídeo encontrado”.
- Mensagens claras em português para falhas do MEGA, inclusive cota de transferência esgotada (HTTP 509 / EOVERQUOTA / erro -17).
- Parser de links mais tolerante: `mega.io`/`mega.app`, `/embed/`, `#` escrito como `%23`, chave em base64 clássico (`+`, `/`, `=`) e arquivo dentro de pasta.

## 1.6.0
- **Navegador** (ícone de globo): WebView com barra de endereço, voltar/avançar/recarregar e barra de carregamento. Entre na sua conta do site normalmente; os cookies ficam salvos (CookieManager). Botão **“Usar esta página no app”** manda o link para Filtrar ou Varrer tudo.
- Todas as requisições (Varrer tudo, Filtrar, Início, tamanho dos arquivos, downloads, segmentos HLS) enviam os cookies do domínio, o mesmo User-Agent do WebView e Referer. O servidor yt-dlp recebe `cookies`/`user_agent`/`referer` (e, na busca em 4K, um `cookie_jar` por domínio) só nesta requisição; a sessão não é guardada no servidor.
- **Configurações → Contas logadas**: lista os sites com sessão, “Sair” de cada um e “Sair de todas”.
- **MEGA** (ícone de nuvem): login na sua conta (API oficial, com prova de trabalho X-Hashcash e 2FA), navegar nas pastas, ver vídeos com tamanho, marcar um ou todos, baixar e descriptografar no celular em qualidade original e enviar ao Telegram. Também aceita links públicos de arquivo/pasta (com chave), inclusive colados no Início/Filtrar. A senha nunca é guardada; só a sessão, criptografada com o Android Keystore.
