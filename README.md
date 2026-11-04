# Baixar pro Telegram

App Android: cole o link de um site → o app percorre o site (mesmo domínio, profundidade/limite configuráveis) → lista todos os vídeos (tags `<video>`/`<source>`, links .mp4/.webm/.mov/.mkv/.m3u8, players KVS, embeds YouTube/Vimeo/etc. via yt-dlp no servidor `baixar-video-ezck.onrender.com`) → copia os links → baixa e envia cada um para as **Mensagens Salvas** do Telegram (TDLib, login com seu api_id/api_hash).

Build: `./gradlew assembleRelease` (keystore em `keystore/`, fora do git).

## 1.5.0
- Novo visual Material 3 com menu inferior: Início, Varrer tudo, Filtrar, Fila, Histórico + Configurações.
- Início: vários links de uma vez (um por linha) e botão Baixar.
- Varrer tudo: varre o site (mesmo domínio, limite de páginas/profundidade) e baixa tudo automaticamente.
- Filtrar: só os vídeos de uma página, listados com tamanho e já marcados.
- Fila com progresso/status, Histórico com Reenviar, tamanho antes de baixar, Testar conexão, qualidade e envio automático.
