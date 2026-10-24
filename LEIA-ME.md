# Tela Limpa 2: launcher para Android TV

Tela inicial limpa, leve e sem anúncios, feita para a TV Aiwa do Rogério.

## O que aparece na tela
- **Saudação** ("Bom dia, Rogério"), **relógio** e **data**.
- **Clima de Nova Friburgo** (temperatura, condição, máxima e mínima), atualizado a cada 30 minutos.
- **Favoritos** (até uma linha cheia) e **Mais usados** (contados pelo próprio launcher).
- **Indicações da semana**: filmes e séries em alta (TMDB). Clique num pôster para ver a sinopse e buscar o trailer.
- **Todos os apps**, mais os atalhos **Configurações** e **Organizar**.

Sem anúncios, sem rastreadores, sem serviços em segundo plano. Só usa a internet enquanto a tela está aberta,
e apenas para o clima (Open-Meteo) e as indicações (TMDB). Nenhuma biblioteca extra.

## Controle remoto
- **OK** num app: abre.
- **Organizar** (ou tecla Menu): clique num app para alternar **Normal → ★ Favorito → Oculto → Normal**.
  Clique em **Concluir** (ou Voltar) para sair.
- Na primeira abertura, YouTube, Netflix, Globoplay, Prime Video e Disney+ (se instalados) já vêm como favoritos.

## Personalizar (arquivo `app/src/main/java/com/rogerio/telalimpa/Config.java`)
- `USER_NAME`: nome na saudação.
- `CITY`, `LATITUDE`, `LONGITUDE`: cidade do clima.
- `TMDB_API_KEY`: chave grátis para ativar as indicações. Como obter:
  1. Crie uma conta em themoviedb.org.
  2. Configurações > API > solicite uma chave (tipo "Developer", uso pessoal).
  3. Copie a **API Key (v3 auth)** e cole entre as aspas, por exemplo `"abc123..."`.
  Sem a chave, o resto funciona normalmente e a linha de indicações mostra um aviso.

Depois de editar no GitHub (lápis ✏️ > Commit changes), a compilação roda sozinha em **Actions**.

## Gerar o APK
No GitHub, aba **Actions** > execução mais recente > **Artifacts** > `tela-limpa-apk`.
(Ou **Run workflow** em "Compilar APK".)

## Instalar na TV
- **Pendrive:** copie o `app-debug.apk`, instale um gerenciador de arquivos pela Play Store da TV,
  permita "fontes desconhecidas" para ele e abra o APK.
- **ADB:** `adb connect IP_DA_TV:5555` e `adb install -r app-debug.apk`.

## Tornar a tela inicial padrão
Aperte **Home**. Se a TV perguntar, escolha **Tela Limpa > Sempre**. Se não perguntar:
```
adb shell cmd package set-home-activity com.rogerio.telalimpa/.MainActivity
```
Em algumas versões do Android TV a troca do Home só funciona assim.

## Desfazer
`adb uninstall com.rogerio.telalimpa`: a TV volta ao launcher original.
Não desative o launcher original até confirmar que este funciona bem.
