# Tela Limpa: launcher minimalista para Android TV

Relógio + grade só com os seus apps. Sem anúncios, sem recomendações, sem internet,
sem serviços em segundo plano e sem nenhuma dependência extra (leve em RAM e energia).

## Como usar o controle remoto
- **Clicar** num app: abre.
- Tile **Organizar** (ou tecla Menu): entra no modo em que clicar num app o **oculta/mostra**.
  Clique em **Concluir** (ou Voltar) para sair.
- Tile **Configurações**: abre as configurações da TV.

## 1. Gerar o APK (escolha UMA opção)

### Opção A: sem instalar nada (GitHub)
1. Crie uma conta grátis em github.com e um repositório novo (ex.: `tela-limpa`).
2. Envie TODO o conteúdo desta pasta (inclusive a pasta oculta `.github`) para o repositório.
3. Aba **Actions** > **Compilar APK** > **Run workflow**.
4. Ao terminar (uns 3 a 5 minutos), abra a execução e baixe o arquivo **tela-limpa-apk**
   (é um zip com o `app-debug.apk`).

### Opção B: Android Studio (no PC)
1. Abra esta pasta no Android Studio e aguarde a sincronização.
2. Menu **Build > Build APK(s)**. O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Instalar na Aiwa
1. Na TV: Configurações > Sobre > toque 7x em "Build" > Opções do desenvolvedor >
   ligue **Depuração USB** (e/ou **Depuração de rede**).
2. No PC (com o ADB / platform-tools):
   ```
   adb connect IP_DA_TV:5555
   adb install app-debug.apk
   ```
   (Alternativa: copie o APK para um pendrive e instale com um gerenciador de arquivos
   da Play Store da TV, permitindo "fontes desconhecidas".)

## 3. Definir como tela inicial
- Aperte o botão **Home**. Se a TV perguntar qual launcher usar, escolha **Tela Limpa** > "Sempre".
- Se não perguntar, tente:
  ```
  adb shell cmd package set-home-activity com.rogerio.telalimpa/.MainActivity
  ```
  (funciona em algumas versões; em outras o Android TV não permite trocar o Home sem root.)

## Desfazer
`adb uninstall com.rogerio.telalimpa`: a TV volta ao launcher original.
Não desative o launcher original até confirmar que o Tela Limpa funciona bem.
