# Haval Climate Control

## Versioning rule
Before every commit+push, increment the version in `app/build.gradle.kts`:
- `versionCode` → +1 (integer, always increments by 1)
- `versionName` → semver: patch for fixes, minor for new features, major for breaking changes

The `versionName` is what the update button in `MainActivity.kt` compares against GitHub Releases tags to decide whether to offer a download. Tags in GitHub releases must match `versionName` exactly (e.g. tag `v1.0.1` for `versionName = "1.0.1"`).

## Canais de release (estável × preview)

Há dois canais, e a separação **não** vem do nome da versão — vem da flag `prerelease`
do GitHub:

| | branch | tag | release | quem enxerga |
|---|---|---|---|---|
| estável | `master` | `v1.21.0` | normal | todo mundo |
| preview | `preview` | `v1.21.0-preview.<n>` | `--prerelease` | só quem já está num APK `-preview` |

- O app estável consulta `/releases/latest`, que **por definição da API do GitHub nunca
  devolve um prerelease** — é essa a barreira. O sufixo `-preview` sozinho não protegeria
  nada, porque a comparação numérica o ignora.
- O app de preview consulta `/releases` (lista completa) e pega o maior. Como
  `1.21.0-preview.7 < 1.21.0` no semver, o testador **volta sozinho para o estável**
  quando o release limpo sai. Não existe caminho de volta manual.
- `build.gradle.kts` guarda sempre a versão **limpa**, nos dois branches. O sufixo
  `-preview.<n>` é injetado pelo CI (`.github/workflows/build.yml`) só quando
  `github.ref_name == "preview"`.
- O `<n>` é um contador **sequencial por versão base, começando em 2**: o CI lê o maior
  prerelease já publicado para aquela base e soma 1. Não é o `github.run_number`, que é
  compartilhado com os builds do master e pularia (`preview.7`, depois `preview.11`).
  Quando a versão base muda, a contagem recomeça no 2 — e tudo bem, porque
  `1.23.0-preview.2 > 1.22.1-preview.9` no semver.
- Os dois APKs compartilham o mesmo `applicationId`: um instala por cima do outro, e o
  bootstrap do Shizuku/uid continua valendo. Para distinguir na tela do carro, o
  cabeçalho mostra um selo **PREVIEW** quando o `versionName` traz o sufixo.
- Ao mergear `preview` → `master`, o `versionCode` do master precisa ficar **>=** o que
  o preview publicou, senão o APK estável não instala por cima (downgrade).

## Display do Multimedia Haval (medido em campo — 2026-05-10)

| Campo | Valor |
|---|---|
| Resolução usável (px) | 1792 × 720 |
| Resolução física real (px) | 1920 × 720 |
| Área ocupada pela status bar | 128 px na horizontal |
| Tamanho usável (dp) | 1792 × 720 dp |
| screenWidthDp | 1792 dp |
| screenHeightDp | 660 dp ← área útil abaixo da status bar |
| smallestScreenWidthDp | 720 dp |
| Densidade lógica (dpi) | 160 dpi (mdpi) |
| Fator de escala | **1.00** → 1 dp = 1 px exato |
| DPI físico X/Y | 320.0 dpi |
| Proporção W/H | ~2.49 (aprox. 12:5) |

**Regras para layouts nesta tela:**
- Use `dp` normalmente — como o fator é 1.00, dp == px neste device.
- Área de trabalho real do app: **1792 × 660 dp** (descontando a status bar de ~60dp no topo).
- A tela é muito mais larga que alta (2.49:1) — prefira layouts horizontais, evite `Column` longas que precisam de scroll.
- `fillMaxSize()` ocupa os 1792 × 660 dp úteis.

## Cards na Home da MediaCenter (agente único)

O script `app/src/main/res/raw/com_beantechs_mediacenter_card.js` é o **único** agente
Frida no processo `com.beantechs.mediacenter`, e desenha **dois** cards na fileira de
mídia online (1158px): clima (CP virtual 590) e ciclo automático do haval-ev-manager
(CP 591), 536×300 cada, com 24px entre eles.

> O haval-ev-manager já teve agente próprio (v1.7.0 dele) e os dois juntos derrubaram
> o ART: SIGSEGV em `MterpInvokeVirtual`, mesmos `ArtMethod` hookados dos dois lados e
> dois watchdogs com `pkill` + reinjetar a cada 10s. **Um agente só, aqui.**

### Dados do card do ciclo

Vêm de `/data/local/tmp/haval_ev_home_card`, escrito pelo haval-ev-manager — o script
não fala com o veículo para isso (a fase do ciclo só existe nas prefs do outro app):

```
conn=1;auto=1;phase=80;soc=62;model=0;ts=1761300000
```

`phase` 80 = CARGA (verde, modo HEV), 20 = USO (âmbar, modo EV). O traço branco na
barra é o gatilho da virada: ≥75% na CARGA, ≤20% no USO.

Arquivo ausente, linha ilegível ou `ts` com mais de `EV_MAX_AGE` (90s) → o card do
ciclo sai da fileira e fica só o de clima. Mudar o formato exige mudar junto o
`HomeCardState.java` do haval-ev-manager.

### Selo TURBO

O selo **TURBO** do card de clima vem de `/data/local/tmp/haval_home_card_turbo`
(`1`/`0`), escrito pelo serviço só nas transições do turbo — o turbo tira o AUTO, então
sem ele o card mostraria MANUAL.

### Desenho por overlay (sem Frida) — 1.35.0

Opção "Desenho do card" na tela de configurações: **Overlay** (padrão a partir da 1.35.0) ou
**Frida** (o script acima). No overlay, `homecard/HomeCardOverlay.kt` põe uma janela
`TYPE_APPLICATION_OVERLAY` por cima do título "Música online" e da fileira, visível só com a
`MediaCenterActivity` no topo. Nada é injetado na MediaCenter e o watchdog do Frida ignora o card
(`homeCardViaFrida()`).

- **Boot sem Shizuku**: o overlay sobe no `onCreate` do serviço. A janela usa o appop
  `SYSTEM_ALERT_WINDOW` e o topo vem do `UsageStatsManager` (`homecard/UsageTopWatcher.kt`, appop
  `GET_USAGE_STATS`). Os dois são concedidos uma vez em `onShizukuReady` e persistem. Medido no
  haval-engine-reverse em 25/11/2026: assim o card aparece antes do card do Frida.
- **Desenho**: `homecard/HomeCardViews.kt` é um port direto de `drawCard`/`drawEvCard`, com as mesmas
  coordenadas e a mesma paleta. O fundo é **opaco**, porque embaixo estão o título e o ícone do OEM.
- **Dados**: o clima vem do `ClimateStateHolder` (poll de 1 s), com o último retrato salvo em prefs
  device-protected para o boot. O ciclo vem do mesmo `/data/local/tmp/haval_ev_home_card`: leitura
  direta e, se negada, `cat` pelo Shizuku. As regras de idade são as mesmas (90 s).
- **Posição**: `uiautomator dump` pelo Shizuku na 1ª vez que a mídia aparece no topo (sem abrir
  nada), com os bounds de `online_music`, `horizontalScrollView` e `local_media`. Antes de calibrar
  o card não aparece. Há um botão "Recalibrar" na tela.
- **Limite**: diálogos da própria MediaCenter ficam por baixo do overlay.
