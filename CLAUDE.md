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
| preview | `preview` | `v1.21.0-preview.<run>` | `--prerelease` | só quem já está num APK `-preview` |

- O app estável consulta `/releases/latest`, que **por definição da API do GitHub nunca
  devolve um prerelease** — é essa a barreira. O sufixo `-preview` sozinho não protegeria
  nada, porque a comparação numérica o ignora.
- O app de preview consulta `/releases` (lista completa) e pega o maior. Como
  `1.21.0-preview.7 < 1.21.0` no semver, o testador **volta sozinho para o estável**
  quando o release limpo sai. Não existe caminho de volta manual.
- `build.gradle.kts` guarda sempre a versão **limpa**, nos dois branches. O sufixo
  `-preview.<run_number>` é injetado pelo CI (`.github/workflows/build.yml`) só quando
  `github.ref_name == "preview"`.
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
