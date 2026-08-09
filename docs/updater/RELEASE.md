# Релиз по тегам

Как из тега получается подписанный APK в GitHub Releases. `version.properties`
и [`.github/workflows/release.yml`](../../.github/workflows/release.yml) созданы
по этому документу — фрагменты ниже описывают то, что уже лежит в репозитории.

Приложение полагается на то, что выложено в релизе, — контракт ассетов
описан в [ARCHITECTURE.md § Источник правды](ARCHITECTURE.md#источник-правды-github-releases)
и нарушать его нельзя, иначе самообновление перестанет находить APK.

## Версии

Нумерация начинается заново с **0.1.0**: прошлые `versionName 0.6.12.1` при
`versionCode 3` — история без тегов и релизов, продолжать её незачем.

Единственный источник версии — файл `version.properties` в корне:

```properties
# Версия приложения. Тег релиза обязан совпадать: 0.1.0 → v0.1.0.
# versionCode считается из этих трёх чисел (см. app/build.gradle.kts),
# руками его не задают.
VERSION=0.1.0
```

`versionCode` вычисляется формулой `major * 10000 + minor * 100 + patch`:

| Версия | versionCode |
|---|---|
| 0.1.0 | 100 |
| 0.2.3 | 203 |
| 1.0.0 | 10000 |

Из формулы следуют два ограничения, оба проверяются и Gradle, и парсером в
приложении: `minor` и `patch` не больше 99, схема строго трёхчленная —
`0.6.12.1` больше не бывает.

Первый релиз 0.1.0 даст `versionCode 100` против нынешних `3` — код вырастет,
обновление поверх установленной сборки встанет нормально.

### Как это читается в Gradle

Правка в `app/build.gradle.kts` рядом с существующим чтением
`local.properties`:

```kotlin
// Версия живёт в version.properties, а не здесь: тот же файл читает CI,
// сверяя его с тегом. Один источник — нельзя выпустить тег v0.2.0 из
// сборки, которая внутри считает себя 0.1.0.
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (!file.exists()) throw GradleException("Нет version.properties в корне проекта")
    file.inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("VERSION").orEmpty()
val appVersionCode: Int = run {
    val parts = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(appVersionName)
        ?: throw GradleException("VERSION должна быть вида X.Y.Z, сейчас «$appVersionName»")
    val (major, minor, patch) = parts.destructured.toList().map(String::toInt)
    // Та же формула живёт в ReleaseVersion.kt — разойдутся, и приложение
    // начнёт предлагать обновление на самого себя.
    if (minor > 99 || patch > 99) {
        throw GradleException("minor и patch не больше 99: «$appVersionName»")
    }
    major * 10_000 + minor * 100 + patch
}
```

И в `defaultConfig`:

```kotlin
versionCode = appVersionCode
versionName = appVersionName

// Самообновление разрешено только релизной сборке: у debug и prerelease
// другой applicationId, и релизный APK для них — не обновление, а второе
// приложение рядом.
buildConfigField("boolean", "UPDATE_ENABLED", "false")
buildConfigField("String", "UPDATE_REPO", "\"Greem4/neiro\"")
```

в `buildTypes { release { … } }` — переопределение `UPDATE_ENABLED` на `true`.

## Что кладётся в релиз

| Ассет | Зачем |
|---|---|
| `neiro-<версия>.apk` | То, что скачивает и ставит приложение, и то, что человек качает руками |
| `SHA256SUMS.txt` | Сумма APK; приложение сверяет её до установки |
| `mapping-<версия>.txt` | Деобфускация стектрейсов R8. Без него разбор краша из релиза невозможен: сборку с теми же условиями через месяц не повторить |

Ровно один `.apk` на релиз — приложение не выбирает между несколькими.

Описание релиза (`body`) собирается из коммитов между прошлым и текущим тегом.
Русские однострочные сообщения коммитов, принятые в проекте, для этого и
годятся: список изменений читается как есть, без ручной правки.

## Workflow

Файл `.github/workflows/release.yml`. Существующий `ci.yml` не трогаем: он
гоняет тесты на каждый push и продолжит это делать.

```yaml
name: Release

on:
  push:
    tags: ['v*']
  workflow_dispatch:

permissions:
  contents: write

jobs:
  release:
    name: Сборка и публикация
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0          # нужен весь лог: заметки собираются с прошлого тега

      - name: Сверяю тег с version.properties
        run: |
          case "$GITHUB_REF" in
            refs/tags/*) ;;
            *) echo "::error::запускать нужно по тегу vX.Y.Z, а не по $GITHUB_REF_NAME"; exit 1 ;;
          esac
          TAG="${GITHUB_REF_NAME#v}"
          SRC="$(sed -n 's/^VERSION=//p' version.properties | tr -d '[:space:]')"
          echo "тег: $TAG, version.properties: $SRC"
          if [ "$TAG" != "$SRC" ]; then
            echo "::error::тег $GITHUB_REF_NAME не совпадает с версией $SRC в version.properties"
            exit 1
          fi
          echo "VERSION=$SRC" >> "$GITHUB_ENV"

      - name: Установить JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Настроить Gradle
        uses: gradle/actions/setup-gradle@v4

      # Секреты восстанавливаются в файлы, которых нет в репозитории.
      # local.properties собирается целиком здесь: build.gradle.kts читает
      # ключи YClients и push именно оттуда.
      - name: Восстановить секреты
        env:
          KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
          GOOGLE_SERVICES_BASE64: ${{ secrets.GOOGLE_SERVICES_JSON_BASE64 }}
        run: |
          echo "$KEYSTORE_BASE64" | base64 -d > app/release.jks
          echo "$GOOGLE_SERVICES_BASE64" | base64 -d > app/google-services.json
          cat > local.properties <<EOF
          RELEASE_STORE_FILE=release.jks
          RELEASE_STORE_PASSWORD=${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS=${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD=${{ secrets.RELEASE_KEY_PASSWORD }}
          NEIRO_PUSH_API_BASE_URL=${{ secrets.NEIRO_PUSH_API_BASE_URL }}
          NEIRO_PUSH_API_KEY=${{ secrets.NEIRO_PUSH_API_KEY }}
          EOF

      - name: Юнит-тесты
        run: ./gradlew testDebugUnitTest

      - name: Сборка релиза
        run: ./gradlew assembleRelease

      # Gradle называет файл neiro-v0.1.0-release.apk (см. androidComponents
      # в app/build.gradle.kts). В релизе имя должно быть neiro-0.1.0.apk —
      # его ищет самообновление и его же скачивают руками.
      - name: Собрать ассеты
        run: |
          mkdir -p dist
          cp "app/build/outputs/apk/release/neiro-v$VERSION-release.apk" \
             "dist/neiro-$VERSION.apk"
          cp app/build/outputs/mapping/release/mapping.txt \
             "dist/mapping-$VERSION.txt"
          cd dist && sha256sum "neiro-$VERSION.apk" | tee SHA256SUMS.txt

      - name: Заметки к релизу
        run: |
          PREV="$(git describe --tags --abbrev=0 "$GITHUB_REF_NAME^" 2>/dev/null || true)"
          {
            echo '## Установка'
            echo
            echo "Скачайте \`neiro-$VERSION.apk\` и откройте на телефоне."
            echo 'Android спросит разрешение на установку из этого источника —'
            echo 'разрешить нужно один раз. Дальше приложение обновляет себя само.'
            echo
            echo '## Что изменилось'
            echo
            if [ -n "$PREV" ]; then
              git log --pretty='- %s' "$PREV..$GITHUB_REF_NAME"
              echo
              echo "[Все изменения с $PREV](${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/compare/$PREV...$GITHUB_REF_NAME)"
            else
              echo '- первый релиз'
            fi
            echo
            echo '## Контрольная сумма'
            echo
            echo '```'
            cat dist/SHA256SUMS.txt
            echo '```'
          } > dist/NOTES.md
          cat dist/NOTES.md

      - name: Публикация
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          if gh release view "$GITHUB_REF_NAME" > /dev/null 2>&1; then
            gh release edit "$GITHUB_REF_NAME" \
              --title "Neiro $VERSION" --notes-file dist/NOTES.md
            gh release upload "$GITHUB_REF_NAME" --clobber \
              "dist/neiro-$VERSION.apk" "dist/mapping-$VERSION.txt" dist/SHA256SUMS.txt
          else
            gh release create "$GITHUB_REF_NAME" \
              --title "Neiro $VERSION" --notes-file dist/NOTES.md \
              "dist/neiro-$VERSION.apk" "dist/mapping-$VERSION.txt" dist/SHA256SUMS.txt
          fi

      # Ключи не должны пережить сборку даже на одноразовой машине.
      - name: Убрать секреты
        if: always()
        run: rm -f app/release.jks app/google-services.json local.properties
```

## Секреты

Заводятся один раз, в `Settings → Secrets and variables → Actions` или
командой. Значения берутся из локального `local.properties` и файлов, которые
в git не лежат.

```bash
# keystore и google-services.json — в base64 одной строкой
base64 -i /путь/к/neiro-release.jks | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 -R Greem4/neiro
base64 -i app/google-services.json  | tr -d '\n' | gh secret set GOOGLE_SERVICES_JSON_BASE64 -R Greem4/neiro

# остальное — как есть
gh secret set RELEASE_STORE_PASSWORD   -R Greem4/neiro
gh secret set RELEASE_KEY_ALIAS        -R Greem4/neiro
gh secret set RELEASE_KEY_PASSWORD     -R Greem4/neiro
gh secret set NEIRO_PUSH_API_BASE_URL  -R Greem4/neiro
gh secret set NEIRO_PUSH_API_KEY       -R Greem4/neiro
```

| Секрет | Откуда взять |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Файл из `RELEASE_STORE_FILE` в `local.properties` |
| `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | `local.properties` |
| `GOOGLE_SERVICES_JSON_BASE64` | `app/google-services.json` |
| `NEIRO_PUSH_API_BASE_URL`, `NEIRO_PUSH_API_KEY` | `local.properties`, ключ также `ssh roster-b3 'grep ^API_KEY= ~/neiro-push/.env'` |

**Keystore незаменим.** Потеряется файл или пароль — обновить установленное
приложение станет нечем: подпись не совпадёт, и всем придётся удалять Neiro и
ставить заново, теряя локальный архив. Копия ключа должна лежать не только на
рабочем Mac.

Секретов `YCLIENTS_PARTNER_TOKEN` и `YCLIENTS_COMPANY_ID` в сборке больше нет:
ключи YClients живут только в `.env` на Pi, приложение ходит к ним через прокси
([docs/neiro-push/](../neiro-push/README.md)). Заведённые ранее секреты GitHub
workflow не читает — их можно удалить.

В публичный APK уезжает один секрет — `NEIRO_PUSH_API_KEY`, и он открывает
ровно попытку входа по логину и паролю ([RISKS.md § Секреты в публичном
APK](RISKS.md#секреты-в-публичном-apk)).

## Как выпустить версию

```bash
# 1. поднять версию
vim version.properties          # VERSION=0.2.0
git add version.properties && git commit -m "Поднял версию до 0.2.0"

# 2. тег и пуш
git tag v0.2.0
git push origin main
git push origin v0.2.0

# 3. смотреть сборку
gh run watch -R Greem4/neiro
```

Дальше ничего делать не нужно: workflow соберёт, подпишет, посчитает сумму,
соберёт заметки и создаст релиз. Телефоны увидят его в течение суток.

### Чек-лист перед тегом

- [ ] `version.properties` поднят и закоммичен;
- [ ] `./gradlew testDebugUnitTest` проходит локально;
- [ ] изменения смёржены в `main`, тег ставится на тот коммит, что уехал;
- [ ] сообщения коммитов с прошлого тега читаемы — они и станут описанием
      релиза;
- [ ] версия ставится поверх предыдущей на живом телефоне (для 0.1.0 — руками,
      дальше — самим приложением).

### Если что-то пошло не так

Тег уже запущен, а сборка упала — исправить, удалить тег и поставить заново:

```bash
git tag -d v0.2.0 && git push origin :refs/tags/v0.2.0
# правки, коммит
git tag v0.2.0 && git push origin v0.2.0
```

Так можно, **пока релиз не опубликован**. Если APK уже полежал в Releases и
кто-то мог его скачать — версия сгорела: нужен `0.2.1`. Иначе у двух телефонов
будет одинаковый `versionCode` с разным содержимым, и второй никогда не
обновится на исправленную сборку.

Откат к прошлой версии самообновлением невозможен — Android не ставит APK с
меньшим `versionCode` поверх большего. Лечится выпуском новой версии с
откаченным кодом, а не понижением номера.

## Запасной путь: сборка локально

Нужен, если CI недоступен или ключи решено не отдавать в GitHub. Собирается
тем же кодом, разница только в том, кто нажимает кнопку:

```bash
./gradlew assembleRelease
V=$(sed -n 's/^VERSION=//p' version.properties)
mkdir -p dist
cp "app/build/outputs/apk/release/neiro-v$V-release.apk" "dist/neiro-$V.apk"
cp app/build/outputs/mapping/release/mapping.txt "dist/mapping-$V.txt"
(cd dist && shasum -a 256 "neiro-$V.apk" > SHA256SUMS.txt)

gh release create "v$V" --title "Neiro $V" --generate-notes \
  "dist/neiro-$V.apk" "dist/mapping-$V.txt" dist/SHA256SUMS.txt
```

Контракт ассетов тот же — приложение не отличит такой релиз от собранного
в CI.
