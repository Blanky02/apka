# Vinyl 🎵

Osobisty, natywny klient streamingowy na Androida — gra w najwyższej dostępnej
jakości (do **24-bit/192 kHz**, FLAC/HI-RES z katalogu Tidal przez Monochrome API),
z drugim źródłem **Octave** dla brakujących utworów.

> „Monochrome nie ma wszystkich piosenek” — więc łączymy dwa źródła i deduplikujemy
> wyniki. Aplikacja celowo jest prosta: szukasz, klikasz, słuchasz.

## Funkcje (v0.1)

- 🔍 **Wyszukiwarka łącząca 2 źródła** — Monochrome (zawsze) + Octave (opcjonalnie),
  z deduplikacją i badge'ami źródła/jakości
- ▶️ **Odtwarzacz Media3 (ExoPlayer)** — kolejka, play next, add to queue,
  skip, shuffle, repeat (off/all/one), seek z paskiem postępu
- 📢 **Powiadomienie z kontrolkami** (foreground service `mediaPlayback`) +
  przyciski w słuchawkach/pilocie BT
- 🎚️ **Wybór jakości**: Niska / Wysoka / Lossless / Hi-Res / Hi-Res Lossless
  (domyślnie najwyższa — API samo downgrade'uje, gdy utwór nie istnieje w wyższej)
- 🌗 Motyw jasny / ciemny / systemowy (ciepła „winylowa” paleta)
- 🔧 **Diagnostyka API w aplikacji** — log wywołań w Ustawieniach (kluczowe przy
  niedokumentowanych endpointach)
- 🔁 **Failover instancji Monochrome** — kilka mirrorów, automatyczne przełączanie
- 🧪 Testy jednostkowe (parser JSON, kolejka, modele)

## Jak zbudować APK

### Opcja A — GitHub Actions (zalecana)

**Włączanie CI (raz):** workflow leży w `ci/build-apk.yml`. Skopiuj go do
`.github/workflows/build-apk.yml`:

```bash
mkdir -p .github/workflows
cp ci/build-apk.yml .github/workflows/
git add .github/workflows/build-apk.yml
git commit -m "ci: workflow budowania APK"
git push
```

(albo w GitHubie: *Add file* → *Create new file* → wklej zawartość `ci/build-apk.yml`)

Po tym każdy push na `main`/`arena/**` uruchamia build:

1. Zmarguj gałąź do `main` (workflow publikuje wtedy **Release** z APK).
2. Albo sprawdź zakładkę **Actions** po dowolnym pushu — artefakt `vinyl-apk`
   zawiera gotowe pliki:
   - `app-debug.apk` — **podpisany debugiem, instalujesz od razu na telefonie**
   - `app-release-unsigned.apk` — do podpisania własnym kluczem (Play Store)

### Opcja B — lokalnie (Android Studio)

1. Otwórz repo w Android Studio (nawigacja: Project → Open).
2. Studio samo pobra Gradle 8.11.1 i SDK (zgodnie z `gradle-wrapper.properties`).
3. `Run 'app'` na podłączonym telefonie albo **Build → Build APK(s)**.

Wymagania: JDK 17, Android SDK 35.

## Architektura

```
UI (Jetpack Compose, Material 3)
 │  VinylRoot → Szukaj / Kolejka / Ustawienia + MiniPlayer + NowPlaying
 ▼
PlayerRepository (stan: kolejka, pozycja, tryby)
 │  MediaController (bind do foreground service)
 ▼
PlayerService (MediaSessionService + ExoPlayer + powiadomienie)
 ▲
DataStore (jakość, motyw, endpointy Octave)
SourceManager ── MonochromeSource (failover instancji, /search /track /cover)
              └─ OctaveSource (szablony endpointów + auto-wykrywanie)
TrackParser — lenient parser JSON (bez sztywnych DTO)
```

Uwagi projektowe:

- **Track jest globalnie unikalny** (`mono-123`, `oct-456`) — utwór zawsze
  należy do jednego źródła, strumienia nie da się „przerzucić” między źródłami
  (inaczej niż deduplikacja wyników wyszukiwania).
- **Adresy strumieni wyłaniane są przed odtworzeniem** (równolegle, 4 na raz,
  z paskiem „Przygotowuję kolejkę x/y”). URL-e CDN mają ograniczoną żywotność —
  przy błędzie odtwarzania pobieramy świeży adres i gramy ponownie (max 2×).
- **Bez sztywnych DTO**: `TrackParser` lenient parsuje wiele kształtów JSON,
  bo API Octave nie ma dokumentacji.

## Źródła muzyki

### Monochrome (główne)
Katalog Tidal, FLAC do 24/192. Endpointy (dane społeczności):
- `GET /search/?s={q}&limit={n}` — wyniki wyszukiwania
- `GET /track/?id={tid}&quality={LOW|HIGH|LOSSLESS|HI_RES|HI_RES_LOSSLESS}` — URL strumienia
- `GET /cover/?id={tid}` — okładka

Serwis działa z wielu instancji — kolejność i adresy:
`MonochromeSource.DEFAULT_INSTANCES` (app sam obsługuje failover).

### Octave (beta)
`https://api.octavestreaming.com` — **nie ma publicznej dokumentacji API**.
W ustawieniach:
- „Adres API” — base URL (domyślnie `https://api.octavestreaming.com`)
- „Szablon wyszukiwarki” — ścieżka z `{query}`, np. `/search?q={query}`
- „Szablon strumienia” — ścieżka z `{id}` i `{quality}`
- **„Wykryj API”** — testuje 10 typowych wariantów i zapisuje działający

Jak poznasz dokładne adresy endpointów (np. z [Discorda Octave](https://discord.gg/5cZAbW3Tbg)),
wklej je w ustawieniach — reszta (parser, odtwarzanie, kolejka) już działa.

## Roadmapa

- [x] **v0.1** — wyszukiwarka + odtwarzacz + kolejka + powiadomienie + jakość + 2 źródła
- [ ] **v0.2** — biblioteka: ulubione, ostatnio słuchane, własne playlisty (Room)
- [ ] **v0.3** — offline: pobieranie FLAC na telefon
- [ ] **v0.4** — widget na ekranie głównym, ekran artist/album, teksty piosenek
- [ ] **v0.5** — korektor dźwięku (AudioProcessor), dynamiczne kolory z okładki

## Status prawny

Projekt osobisty — do użytku własnego/znajomego. Muzyka streamowana z usług
trzecich (Monochrome/Octave); nie rozprzestrzeniaj dalej niż swoje APK.

---
Zbudowano: Kotlin + Jetpack Compose + Media3 + OkHttp + Room (od v0.2).
