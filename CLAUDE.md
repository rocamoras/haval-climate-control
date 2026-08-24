# Haval Climate Control

## Versioning rule
Before every commit+push, increment the version in `app/build.gradle.kts`:
- `versionCode` → +1 (integer, always increments by 1)
- `versionName` → semver: patch for fixes, minor for new features, major for breaking changes

The `versionName` is what the update button in `MainActivity.kt` compares against GitHub Releases tags to decide whether to offer a download. Tags in GitHub releases must match `versionName` exactly (e.g. tag `v1.0.1` for `versionName = "1.0.1"`).

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

## Cards na Home da MediaCenter — convivência com o haval-ev-manager

Os dois apps injetam agentes Frida no MESMO processo (`com.beantechs.mediacenter`) e
desenham um card cada no `online_music_container`:

| App | Script | Slot | Tag da view | CP virtual | Arquivo de controle |
|---|---|---|---|---|---|
| haval-climate-control | `com_beantechs_mediacenter_card.js` | 0 (esquerda) | `hcc-home-card` | 590 | `/data/local/tmp/haval_home_card` |
| haval-ev-manager | `com_beantechs_mediacenter_ev_card.js` | 1 (direita) | `hem-home-card` | 591 | `/data/local/tmp/haval_ev_home_card` |

**Protocolo (baseado só nas views — o processo da MediaCenter não escreve em `/data/local/tmp`):**
- Ninguém chama `removeAllViews()`. A limpeza (`clearForeign`) tira só o que **não**
  está em `CARD_TAGS` — os ícones do OEM saem, o card do parceiro fica. Sem isso os
  dois se apagam em looping a cada tick de 1,5 s.
- A posição sai de `insertIndex()` (ordem de `CARD_TAGS`), não de quem pintou primeiro.
  Cabem os dois: `536 + 24 + 536 = 1096` px nos 1158 px da fileira.
- **Dono** = card de menor slot presente na fileira. Só o dono mexe no que é global:
  título "Mídia online", `topMargin` da fileira e `restore()`. Se o clima desligar com o
  card de carga ativo, ele remove só a própria view e o ev-manager assume no próximo tick.
- `origRowTop`: quem encontra a fileira já içada (`topMargin == ROW_TOP`) assume o valor
  de fábrica medido (`ROW_TOP_DEFAULT = 168`) em vez de gravar 100 como "original".

**fridaserver compartilhado:** é único (`/data/local/tmp/fridaserver`). `stopTarget()` só
o derruba quando nenhum script dos dois apps está injetado (`EXTERNAL_SCRIPTS` em
`FridaUtils`), e o binário não é reextraído com o server de pé (ETXTBSY). Os dois apps
precisam da mesma versão do Frida — **16.7.19**, fixada nos dois workflows de CI.
