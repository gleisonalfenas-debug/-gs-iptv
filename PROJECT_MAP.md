# GS IPTV — Mapa do Projeto

> **Supersede:** Nada (primeira versão do mapa)
> **Origem:** Solicitação do usuário: "criar um mapa do meu projeto, pra visualizar"
> **Linkado de:** Nenhum documento anterior — criado agora como referência única

## Estrutura de Diretórios
```
-gs-iptv/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/gs/iptv/          # Código Kotlin (27 arquivos)
│   │   │   ├── res/
│   │   │   │   ├── layout/                # 4 XMLs de layout
│   │   │   │   ├── drawable/              # Backgrounds, ícones, shapes
│   │   │   │   ├── values/                # Strings, colors, styles
│   │   │   │   └── xml/                   # Configurações (backup, network)
│   │   │   └── AndroidManifest.xml
│   │   ├── build.gradle.kts
│   │   └── keystore/gs.jks                # Assinatura do APK
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── .github/workflows/android.yml          # CI/CD → APK + Release GitHub
├── node/iptv-player.html                  # Player web separado (não usado no app)
├── python/                                # Scripts auxiliares
└── LEIA-ME.md
```

---

## Arquivos Kotlin Principais (por responsabilidade)

### 🎮 **UI / Navegação / Ciclo de Vida**
| Arquivo | Linhas | Responsabilidade |
|---------|--------|------------------|
| `MainActivity.kt` | 52k | **God Activity** — sidebar, páginas, foco, carregamento, player launcher |
| `PlayerActivity.kt` | 30k | Tela cheia ExoPlayer, overlay, troca canal, EPG, gestos |
| `SeriesActivity.kt` | 9k | Lista de episódios de série |
| `SplashActivity.kt` | 5k | Splash, verifica update, carrega lista inicial |
| `SendListActivity.kt` | 2k | QR code p/ receber lista do celular |

### 📦 **Dados / Cache / Parsing**
| Arquivo | Linhas | Responsabilidade |
|---------|--------|------------------|
| `Store.kt` | 26k | **Central de dados** — cache TSV, parsing M3U/Xtream, prefs, favoritos, abas |
| `Xtream.kt` | 11k | API Xtream Codes (login, sections, EPG, account info) |
| `Epg.kt` | 8k | Parse XMLTV, cache programação, current/next |
| `Scheduler.kt` | 5k | Agendamento jogos (AlarmManager + overlay permission) |

### 🎨 **Views Custom / Animações**
| Arquivo | Linhas | Responsabilidade |
|---------|--------|------------------|
| `CardFan.kt` | 11k | `PosterCard`, `CardFan`, `SpiralView`, `ShowcaseView` — animações 3D |
| `GlobeView.kt` | 9k | Globo 3D animado (não usado ativamente?) |
| `Logo3DView.kt` | 7k | Logo 3D rotativo |
| `Motion.kt` | 3k | Ken Burns, transições |
| `TvList.kt` | 2k | `RecyclerView` travado nas bordas p/ controle remoto |

### 🔄 **Adapters / Listas**
| Arquivo | Linhas | Responsabilidade |
|---------|--------|------------------|
| `NewAdapters.kt` | 17k | `CategoryAdapter`, `LiveRowAdapter`, `GridAdapter`, `ChipAdapter`, `GameAdapter`, `BigTileAdapter` |
| `Home.kt` | 12k | `HRow`, `HomeAdapter`, `TileAdapter`, `StoryAdapter` (fileiras horizontais) |
| `Adapters.kt` | 8k | Adapters legados (não usados?) |

### 🌐 **Rede / Utilitários**
| Arquivo | Linhas | Responsabilidade |
|---------|--------|------------------|
| `Http.kt` | 4k | OkHttpClient + DNS-over-HTTPS, connection pooling |
| `Net.kt` | 3k | Helpers de stream, timeout, user-agent |
| `ListServer.kt` | 6k | HTTP server local p/ QR code (recebe lista do celular) |
| `Updater.kt` | 3k | Auto-update via GitHub Releases (update.json) |
| `Diag.kt` | 4k | Diagnóstico de rede, cache, memória |
| `GsApp.kt` | 2k | Application class + crash handler |
| `Guide.kt` | 3k | Dialog de programação do dia (seta direita no canal) |
| `Games.kt` | 6k | Busca jogos de futebol (API aberta) |
| `PlayerFactory.kt` | 9k | Cria ExoPlayer, DRM, track selector |
| `Buffer.kt` / `DnsMode.kt` | — | Enums de configuração |

---

## Layouts XML
| Arquivo | Descrição |
|---------|-----------|
| `activity_main.xml` | **Principal** — sidebar (rail) + header + FrameLayout com 8 páginas (Home, TV, Filmes, Séries, Jogos, Kids, Conta, Painel) |
| `activity_player.xml` | Player fullscreen — ExoPlayerView + overlay controles + info EPG |
| `activity_series.xml` | Lista episódios — RecyclerView vertical + header série |
| `activity_send_list.xml` | QR code + instruções p/ enviar lista do celular |

---

## Fluxo Principal

```
SplashActivity
    │
    ├─► tem lista? ──Não──► SendListActivity (QR code) ──► salva ──► MainActivity
    │
    └─► Sim ──► MainActivity
                    │
                    ├─► Sidebar (rail) — 7 abas + Buscar
                    │
                    ├─► Páginas (View.VISIBLE/GONE)
                    │   ├─ HOME  → Hero + 6 fileiras HRow (TileAdapter/StoryAdapter)
                    │   ├─ TV    → TvList (categorias) + TvList (canais) + InfoPanel
                    │   ├─ FILMES/SÉRIES → HRow (chips) + TvList (grid 5 colunas)
                    │   ├─ JOGOS → HRow (dias) + TvList (lista)
                    │   ├─ KIDS  → TvList (grid 3 colunas)
                    │   ├─ CONTA → ScrollView 2 colunas (Preferências | Info)
                    │   └─ BUSCA → Dialog → resultados no grid VOD
                    │
                    ├─► Carregamento assíncrono (LoadStatus + loadBar)
                    │   ├─ Canais → Filmes → Séries (sequencial)
                    │   └─ Cache TSV válido (12h) = abre instantâneo
                    │
                    └─► PlayerActivity (fullscreen)
                        ├─ ExoPlayer (HLS/TS)
                        ├─ Overlay: info, EPG, troca canal (cima/baixo)
                        ├─ Long press OK → estrelas
                        └─ Seta direita → Guide (programação do dia)
```

---

## Configurações (SharedPreferences `gs`)

| Chave | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `lite` | Boolean | `true` | Modo simples (sem imagens, animações) |
| `show_clock` | Boolean | `false` | Relógio fixo nos canais |
| `show_logo` | Boolean | `true` | Logo no canto dos canais |
| `auto_last` | Boolean | `false` | Abrir no último canal |
| `kids_safe` | Boolean | `false` | Modo seguro Kids (pede PIN) |
| `kids_pin` | String | `""` | PIN 4 dígitos |
| `favs` | StringSet | `∅` | URLs de canais favoritos |
| `last` | String | `null` | Última URL tocada |
| `route` | String | `ts` | `ts` ou `hls` (rota preferida) |
| `seen_version` | Int | `-1` | Versão já vista (para changelog) |

---

## CI/CD (GitHub Actions)

**Trigger:** `push` em `main` tocando `android/**`

**Jobs:**
1. `gradle -p android assembleRelease` → `app-release.apk`
2. Copia → `gs-iptv.apk`
3. Gera `update.json` com `versionCode = github.run_number`
4. `softprops/action-gh-release` → cria Release `v1.0.{run_number}` com APK + JSON

**Auto-update na TV:** `Updater.kt` baixa `update.json` → compara `versionCode` → baixa APK → `Intent.ACTION_INSTALL_PACKAGE`

---

## Dependências Principais (build.gradle.kts)

```kotlin
// UI
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.activity:activity:1.9.0")

// Imagens
implementation("io.coil-kt:coil:2.6.0")

// Player
implementation("com.google.android.exoplayer:exoplayer:2.19.1")
implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
implementation("com.google.android.exoplayer:exoplayer-dash:2.19.1")

// Rede
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

// Corrotinas
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

// Serialização (Xtream)
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

---

## Pontos de Atenção / Dívida Técnica

| Área | Problema | Risco |
|------|----------|-------|
| `MainActivity` | 52k linhas, mistura UI + navegação + lógica + dados | Difícil testar, bugs de foco |
| `Store` | 26k linhas, singleton god object, parsing + cache + prefs | Acoplamento alto, memória |
| `PlayerActivity` | 30k linhas, ExoPlayer + overlay + EPG + gestos | Complexo, edge cases |
| Testes | **Zero testes** (unit/UI) | Regressões silenciosas |
| DI | Nenhum (Hilt/Koin) | Singletons hardcoded |
| Arquitetura | Monólito, sem camadas | Manutenção difícil |

---

## Próximos Passos Sugeridos

1. **Extrair `Store`** → `Repository` (dados) + `DataSource` (cache/remote) + `PrefsManager`
2. **Introduzir `ViewModel`** + `StateFlow` em `MainActivity` / `PlayerActivity`
3. **Adicionar testes** — JUnit para parsing, Espresso/Compose para navegação
4. **Modularizar** — `core:data`, `core:player`, `feature:home`, `feature:tv`, etc.
5. **Hilt/Koin** — injeção de dependência para facilitar testes