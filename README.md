# Baixar pro Telegram

App Android: cole o link de um site → o app percorre o site (mesmo domínio, profundidade/limite configuráveis) → lista todos os vídeos (tags `<video>`/`<source>`, links .mp4/.webm/.mov/.mkv/.m3u8, players KVS, embeds YouTube/Vimeo/etc. via yt-dlp no servidor `baixar-video-ezck.onrender.com`) → copia os links → baixa e envia cada um para as **Mensagens Salvas** do Telegram (TDLib, login com seu api_id/api_hash).

Build: `./gradlew assembleRelease` (keystore em `keystore/`, fora do git).

## 1.9.22
- Navegador: além dos pedidos de rede, lê sozinho o endereço dos players <video>/<source> da página (quando é um arquivo de verdade, não blob). O diagnóstico mostra quantos players a página tem.

## 1.9.21
- Navegador: os pedidos de vídeo da página são lidos por até 5 min (antes 40 s), para aulas e players que só carregam depois de um clique.
- Reconhece listas HLS/DASH pelo tipo da resposta, mesmo sem ".m3u8"/".mpd" no endereço.
- "Copiar diagnóstico" mostra cada pedido de mídia visto (navegador, service worker ou página), o tipo e se foi aceito ou descartado (e por quê). Sem links.

## 1.9.20
- Navegador acha o vídeo sem precisar dar play em sites que carregam a lista HLS (.m3u8) sozinhos: os pedidos de vídeo que a página faz são lidos automaticamente por 40 s depois de abrir (antes só no "Procurar").
- Pedidos feitos pelo service worker do site agora também entram na detecção (antes ficavam invisíveis para o app).

## 1.9.19
- Corrige o app fechando sozinho por falta de memória (OutOfMemoryError): downloads em pedaços (HLS/DASH) agora têm um limite global de pedaços na memória e esperam quando a memória aperta; os downloads usam HTTP/1.1 (no HTTP/2 a rede enchia a memória antes de o app ler); o app pode usar mais memória (largeHeap).
- Corrige o fechamento "ForegroundServiceDidNotStopInTimeException": os serviços da fila, da varredura e de limpar repetidos usam o tipo "uso especial" (sem o limite de 6 h do dataSync) e, se o Android pedir para parar, param na hora.

## 1.9.18
- Caixa-preta: quando o app fecha sozinho, o motivo fica gravado mesmo com o app fechado (queda no código, queda nativa, travamento/ANR, falta de memória ou WebView). Na próxima vez que abrir, aparece o aviso com botão **Copiar**; o motivo também vai no "Copiar diagnóstico", junto com os últimos passos (sem links).
- Navegador: se a página derrubar o renderizador, o app não fecha mais e o navegador é recriado e recarrega a página. Se a mesma página cair 3 vezes seguidas, ela não é recarregada de novo.
- Mais leve: removida a pré-renderização fora da tela (offscreenPreRaster), que gastava muita memória no navegador e nas páginas ocultas.
- Removida a conta MEGA (login). Links públicos do MEGA continuam funcionando no Início.

## 1.7.4
- **Só vídeos**, em todas as abas. O interruptor “Incluir fotos” foi removido: o app não coleta nem envia imagens (capas, miniaturas ou fotos de conteúdo).
- Se o link colado for uma **listagem** (vários cards com capa + título + duração), o app abre cada card e resolve o stream real (mp4/m3u8/mpd, player, iframe, yt-dlp), na maior qualidade, sem repetir o mesmo vídeo.
- Página de **um único vídeo**: extrai o HTML estático e, em paralelo, consulta o servidor yt-dlp; se ainda não houver stream, abre um WebView oculto (~15 s), dá play no player e intercepta os pedidos de mídia (incluindo iframes). Fica um item só, na maior qualidade.
- Bytes mágicos no download e de novo no envio ao Telegram: um arquivo de imagem nunca é enviado como vídeo.

## 1.7.3
- O mesmo vídeo em várias qualidades (360/720/1080, HLS, DASH, array de sources do player) vira **um item só**, sempre na maior qualidade. HLS master escolhe a variante de maior BANDWIDTH/RESOLUTION; vários mp4, o de maior resolução ou tamanho.
- Dedup entre páginas (URL canônica, tokens de qualidade e assinatura de CDN removidos) e contra o histórico de já enviados.
- A busca mostra diagnóstico na tela: método, páginas de busca, cards achados, vídeos resolvidos e falhas (por que faltou algum). Cards cobrem layouts comuns (thumb, .video-item, .item, article, li com link+img+título).

## 1.7.2
- Não baixa mais capa/miniatura no lugar do vídeo: pôster, `og:image` quando há `og:video`, thumbs de card, `thumbnailUrl` de VideoObject e URLs com thumb/poster/cover/capa/hqdefault são ignorados.
- Card da busca com miniatura aponta para a página da aula: o app abre a página e pega o stream real (mp4/m3u8/mpd, player, iframe, yt-dlp).
- Busca por nome vem só com vídeos; fotos entram apenas com o interruptor “Incluir fotos” (desligado por padrão).
- Se a busca do site achou resultados, todos os cards entram (sem filtro estrito). Paginação até 50 páginas, “load more”/JSON, e WebView com scroll se a página estática trouxer poucos itens.
- Na hora de baixar, o tipo é conferido (Content-Type e bytes mágicos). Se um “vídeo” veio imagem ou HTML, tenta de novo pela página/yt-dlp; senão marca erro e não envia.

## 1.7.1
- Com um nome, o app tenta primeiro a **busca do próprio site** (formulário, OpenSearch, `?s=`/`?q=`/`/search`/`/busca`, WordPress REST e APIs JSON no HTML). Abre as páginas de resultado em paralelo, aplica o filtro estrito e já mostra a prévia. Só faz a **varredura completa** se a busca do site não existir ou não devolver nada. A interface mostra o método usado (“Busca do site” ou “Varredura completa”).
- Varredura bem mais rápida: dezenas de páginas em paralelo, timeouts curtos, HTML limitado, ignora arquivos estáticos, lê sitemap.xml, WebView só no fim e só se não achou nada, prévia incrementando na hora.

## 1.7.0
- Busca por nome na aba Varrer tudo (campo abaixo do link): palavra inteira, sem acento/maiúsculas, todas as palavras (AND). `guitarra` não traz `guitarrada`.
- Texto associado só o que está perto da mídia (attrs, figcaption, âncora, heading irmão ou do bloco). Heading Gibson + foto + 2 vídeos marca os três. Sem h1 distante nem título da página em tudo.
- Fotos entram na varredura; ícones/logos pequenos são ignorados. Envio como foto do Telegram (ou documento se passar de 10 MB).
- Com um nome, o app sempre mostra a lista (título, tipo, página) para confirmar e só envia ao tocar em Baixar e enviar.
- Detecção mais forte: content-type em URLs sem extensão, JSON de player/JSON-LD, data-video/data-url, iframes até 2 níveis, DASH via servidor yt-dlp, erros mais claros e WebView oculto se o HTML estático não achar nada.

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
- Todas as requisições (Varrer tudo, Filtrar, Início, tamanho dos arquivos, downloads, segmentos HLS) enviam os cookies do domínio, o mesmo User-Agent do WebView e Referer. O servidor yt-dlp recebe `cookies`/`user_agent`/`referer` no JSON (o backend atual ainda ignora esses campos).
- **Configurações → Contas logadas**: lista os sites com sessão, “Sair” de cada um e “Sair de todas”.
- **MEGA** (ícone de nuvem): login na sua conta (API oficial, com prova de trabalho X-Hashcash e 2FA), navegar nas pastas, ver vídeos com tamanho, marcar um ou todos, baixar e descriptografar no celular em qualidade original e enviar ao Telegram. Também aceita links públicos de arquivo/pasta (com chave), inclusive colados no Início/Filtrar. A senha nunca é guardada; só a sessão, criptografada com o Android Keystore.
