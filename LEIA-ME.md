# GS IPTV – App para Android TV

Player de IPTV para Android TV, TV Box e Fire TV Stick. Você envia a lista pelo celular, usando o QR Code, e o app se atualiza sozinho.

O app só reproduz as listas que você colocar nele. Use apenas listas do seu provedor licenciado ou canais abertos.

## O que o app faz

- Abertura com o logo GS animado
- Abas TV, FILMES, SÉRIES, KIDS e ANIME
- TV ao vivo com categorias, lista de canais e prévia do vídeo ao lado
- Filmes e séries em grade de capas
- Player em tela cheia: nos canais ao vivo, **cima/baixo** troca de canal
- Favoritos (segure **OK** em um canal) e busca
- Abre no último canal assistido
- Aceita **M3U/M3U8** e **Xtream Codes**
- **Envio da lista pelo celular:** a TV mostra um QR Code, você lê com o celular, cola a lista e envia
- **Atualização automática:** quando você abre o app, ele procura uma versão nova, baixa e abre o instalador

---

## Passo a passo para gerar o app (feito uma vez só)

O APK é gerado de graça na nuvem pelo GitHub, sem precisar instalar nada no computador.

### 1. Crie o repositório
1. Crie uma conta em https://github.com
2. Clique em **New repository**, use o nome `gs-iptv` e marque **Public**.
   O repositório precisa ser público para a TV conseguir baixar as atualizações.
   A chave de assinatura não fica nele, então ninguém consegue publicar versões falsas.

### 2. Envie os arquivos
Envie todo o conteúdo desta pasta para o repositório: as pastas `android`, `.github` e este arquivo.
Pelo computador, use **Add file → Upload files** e arraste as pastas.

### 3. Cadastre a chave de assinatura
A chave (`android/keystore/gs.jks`) já está no projeto, protegida por senha. Ela garante que cada versão nova instale por cima da anterior.
No repositório, vá em **Settings → Secrets and variables → Actions → New repository secret** e crie um segredo:

| Nome | Valor |
|---|---|
| `KEYSTORE_PASSWORD` | o texto do arquivo `KEYSTORE_PASSWORD.txt` |

⚠️ Guarde o arquivo da chave (`gs.jks`) e a senha em um lugar seguro. Se perder a chave, as próximas versões não vão instalar por cima da atual.

### 4. Gere o app
Vá em **Actions → Gerar app Android TV → Run workflow**. Em cerca de 5 minutos, o APK aparece em **Releases**.

### 5. Instale na TV
1. Na TV, instale o app **Downloader**, que está na loja da TV.
2. Abra o Downloader e digite:
   `https://github.com/SEU-USUARIO/gs-iptv/releases/latest/download/gs-iptv.apk`
3. Permita a instalação de apps desconhecidos quando a TV pedir.

## Como as atualizações funcionam
Toda vez que algo muda na pasta `android` do repositório, o GitHub gera uma versão nova automaticamente (1.0.2, 1.0.3…).
Na próxima vez que o GS IPTV abrir na TV, ele mostra **"Baixando nova versão…"** e abre o instalador. É só confirmar em **Instalar**.

## Como enviar a lista
1. A TV e o celular precisam estar no mesmo Wi-Fi.
2. No app, aperte o ícone do celular no topo. Na primeira vez, essa tela já abre sozinha.
3. Leia o QR Code com a câmera do celular.
4. Cole o link M3U ou os dados do Xtream e toque em **Enviar para a TV**.
