# Haval Climate Control

Aplicativo Android de controle climático inteligente para veículos Haval, desenvolvido para rodar no display multimídia integrado do veículo. O app intercepta e automatiza comandos do sistema HVAC com base em regras de temperatura, mantendo o habitáculo confortável sem intervenção manual.

---

## Como funciona

O app roda como um **serviço em foreground** que se comunica diretamente com o `IntelligentVehicleControlService` do veículo via SDK proprietário (Beantechs), usando [Shizuku](https://shizuku.rikka.app/) para obter as permissões de sistema necessárias. A cada mudança de propriedade do veículo, o serviço avalia as regras e envia comandos ao HVAC quando necessário.

A interface é um **HMI automotivo** em tela cheia (1792 × 660 dp, proporção ~2.5:1) construído em Jetpack Compose, com tema monocromático escuro e acento verde apenas para estados ativos.

---

## Regras implementadas

### Detecção de partida do carro

Quando o sensor de temperatura interna sai do estado offline (`87 °C` = carro desligado) para uma leitura real:

- **AC ligado automaticamente** ao detectar a partida.
- **Proteção de 30 segundos**: nos primeiros 30 s após a partida o AC não é desligado pelas regras abaixo, mesmo que a temperatura interna esteja abaixo do setpoint.

---

### Bloco A — Controle do AC *(requer `car.hvac.auto_enable = 1`)*

Gerencia o liga/desliga do compressor (`car.hvac.ac_enable`) com base na diferença entre temperatura interna e setpoint do motorista (`car.hvac.driver_temperature`).

#### Desligar o AC

| Condição | Ação |
|---|---|
| Interna ≤ Setpoint − Histerese **e** AC ligado **e** fora da proteção de 30 s | Desliga AC |

#### Ligar o AC

| Condição | Ação |
|---|---|
| Interna ≥ Setpoint + 0,5 °C **e** AC desligado | Liga AC |
| Interna ≥ Setpoint **e** AC desligado por **mais de 1 minuto** | Liga AC |

#### Histerese dinâmica

| Temperatura externa | Histerese aplicada |
|---|---|
| ≤ 28 °C | **0,5 °C** |
| > 28 °C | **1,0 °C** (dias muito quentes — evita ciclos curtos) |

---

### Bloco A — Comfort Curve *(requer `car.hvac.auto_enable = 1`)*

Ajusta a curva de conforto do HVAC (`car.hvac.setting.comfort_curve`) conforme a temperatura interna:

| Temperatura interna | Curva |
|---|---|
| < 22 °C | `0` — mais conservadora |
| 22 °C – 24 °C | `1` — intermediária |
| > 24 °C | `2` — mais agressiva |

---

### Bloco B — Ventilação dos bancos *(independente do modo auto do HVAC)*

Controla o nível de ventilação dos bancos do motorista e passageiro (`car.comfort_setting.driver_seat_ventilation_level` / `car.comfort_setting.passenger_seat_ventilation_level`) com base na temperatura interna:

| Temperatura interna | Nível |
|---|---|
| > 28 °C | `3` — máximo |
| > 26 °C | `2` — alto |
| > 24 °C | `1` — baixo |
| ≤ 24 °C | `0` — desligado |

**Toggle Automático / Desligado** — os cards de ventilação na tela são clicáveis:
- **AUTO** (padrão): lógica acima ativa, nível exibido em tempo real.
- **OFF**: lógica ignorada, bancos zerados imediatamente, UI exibe `--`.

A última escolha é persistida em `SharedPreferences` e restaurada ao reabrir o app.

---

### Bloco C — Aquecimento por temperatura externa *(independente do modo auto do HVAC)*

Controla o aquecimento (`car.hvac.heating_enable`) com base na temperatura externa:

| Temperatura externa | Ação |
|---|---|
| < 20 °C | `heating_enable = 1` — aquecimento ligado |
| ≥ 20 °C | `heating_enable = 0` — aquecimento desligado |

O comando só é enviado quando o valor muda (comparação com cache local).

---

## Interface

- **Tela principal** — layout HMI em 3 colunas: coluna esquerda (temperatura interna + status AC/aquecimento + fluxo de ar animado), coluna central (visualização top-down do carro + temperatura de setpoint + external), coluna direita (ventilação dos bancos + logs de ações).
- **Toggle de controle automático** — desativa/reativa todas as regras de uma vez.
- **Log de ações** — histórico em tempo real com timestamp dos últimos 50 eventos (HVAC e bancos em abas separadas).
- **Auto-update** — verifica novas versões uma vez por dia via GitHub Releases API e oferece download direto na tela.

---

## Arquitetura

```
MainActivity (Compose HMI)
    │
    ├── ClimateStateHolder (Kotlin object — estado reativo compartilhado)
    │       ├── mutableStateOf → UI recompõe automaticamente
    │       └── commandCallback → envia comandos ao serviço
    │
    └── ClimateControlService (Foreground Service)
            ├── Shizuku → permissões de sistema
            ├── IIntelligentVehicleControlService → SDK Beantechs
            ├── vehicleDataListener → dispara evaluateClimateControl() a cada mudança
            └── evaluateClimateControl()
                    ├── Detecção de partida
                    ├── Bloco A — AC + Comfort Curve
                    ├── Bloco B — Ventilação dos bancos
                    └── Bloco C — Aquecimento
```

---

## Requisitos

- Display multimídia Haval com Android (testado no H6 GT 2023)
- [Shizuku](https://shizuku.rikka.app/) instalado e rodando
- Permissão `MANAGE_EXTERNAL_STORAGE` (para instalação de atualizações)

---

## Build & Release

O pipeline CI/CD roda no GitHub Actions a cada push para `master`:

1. Lê `versionName` / `versionCode` do `app/build.gradle.kts`
2. Assina o APK com keystore via secrets
3. Publica GitHub Release com tag `v{versionName}`

O app verifica automaticamente a última release disponível e compara com a versão instalada para oferecer atualização.

---

## Versionamento

| Tipo de mudança | Incremento |
|---|---|
| Bug fix | patch (`1.0.x`) |
| Nova funcionalidade | minor (`1.x.0`) |
| Mudança incompatível | major (`x.0.0`) |

`versionCode` sempre +1 a cada release.
