#!/usr/bin/env bash
# Скриншоты приложения: снять с телефона и разложить в два разных набора.
#
#   ./scripts/screenshots.sh capture calendar-light   # снять экран с телефона
#   ./scripts/screenshots.sh build                    # собрать оба набора
#   ./scripts/screenshots.sh check                    # сверить с лимитами RuStore
#
# Наборы разные не по прихоти: README показывает приложение как есть, вместе
# со строкой статуса, а RuStore элементы интерфейса Android на скриншотах
# запрещает — их приходится срезать. Подробности и что именно снимать —
# docs/screenshots.md.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RAW_DIR="${ROOT_DIR}/docs/images/raw"
README_DIR="${ROOT_DIR}/docs/images"
STORE_DIR="${ROOT_DIR}/store/rustore"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
FFMPEG="${FFMPEG:-ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"

# Сколько пикселей срезать сверху (строка статуса) и снизу (жесты/кнопки).
# Значения под 1080×2400; другой телефон — поменять здесь или через окружение.
CROP_TOP="${CROP_TOP:-110}"
CROP_BOTTOM="${CROP_BOTTOM:-60}"

# Фон подложки RuStore: кадр 9:16 шире снимка, поля закрашиваются фоном темы.
LIGHT_BG="${LIGHT_BG:-0xF8F9FA}"   # LightBackground из theme/Color.kt
DARK_BG="${DARK_BG:-0x121212}"     # DarkBackground

# Требования RuStore: 9:16, рекомендованный 1080×1920, до 3 МБ, до 10 штук.
STORE_W=1080
STORE_H=1920
STORE_MAX_BYTES=$((3 * 1024 * 1024))
STORE_MAX_FILES=10

# README показывает картинки в 240 px шириной — 480 хватает и на retina,
# а репозиторий не пухнет от полноразмерных снимков.
README_W=480

die() { echo "ошибка: $*" >&2; exit 1; }

need_ffmpeg() {
  command -v "${FFMPEG}" >/dev/null 2>&1 && command -v "${FFPROBE}" >/dev/null 2>&1 \
    || die "нет ffmpeg/ffprobe — brew install ffmpeg"
}

cmd_capture() {
  need_ffmpeg
  local name="${1:-}"
  [[ -n "${name}" ]] || die "имя не задано: ./scripts/screenshots.sh capture calendar-light"
  [[ -x "${ADB}" ]] || die "нет adb по пути ${ADB} — задайте ADB=/путь/к/adb"

  "${ADB}" get-state >/dev/null 2>&1 || die "телефон не подключён (adb devices)"

  mkdir -p "${RAW_DIR}"
  local target="${RAW_DIR}/${name}.png"
  "${ADB}" exec-out screencap -p > "${target}"
  [[ -s "${target}" ]] || { rm -f "${target}"; die "снимок пустой"; }

  echo "снято: ${target}  ($(dimensions "${target}"))"
}

# Через ffprobe, а не разбором вывода ffmpeg: у ffmpeg размеры печатаются в
# баннере, который -v error как раз и глушит, а сам вызов без выходного файла
# завершается ошибкой и роняет скрипт под set -e.
dimensions() {
  "${FFPROBE}" -v error -select_streams v:0 \
    -show_entries stream=width,height -of csv=p=0:s=x "$1"
}

background_for() {
  case "$1" in
    *dark*) echo "${DARK_BG}" ;;
    *)      echo "${LIGHT_BG}" ;;
  esac
}

cmd_build() {
  need_ffmpeg
  [[ -d "${RAW_DIR}" ]] || die "нет ${RAW_DIR} — сначала capture"

  shopt -s nullglob
  local raws=("${RAW_DIR}"/*.png)
  ((${#raws[@]})) || die "в ${RAW_DIR} пусто"

  mkdir -p "${README_DIR}" "${STORE_DIR}"
  local built=0

  for raw in "${raws[@]}"; do
    local name bg
    name="$(basename "${raw}" .png)"
    bg="$(background_for "${name}")"

    # README: как на телефоне, только меньше. Ничего не режем — строка
    # статуса на карточке проекта уместна, там показывают живое приложение.
    "${FFMPEG}" -v error -y -i "${raw}" \
      -vf "scale=${README_W}:-2:flags=lanczos" \
      "${README_DIR}/${name}.png"

    # RuStore: срезаем строку статуса и полосу жестов, вписываем в 9:16,
    # поля закрашиваем фоном темы — обрезать содержимое нельзя, магазин
    # сделает это сам и наверняка не там, где надо.
    "${FFMPEG}" -v error -y -i "${raw}" \
      -vf "crop=iw:ih-${CROP_TOP}-${CROP_BOTTOM}:0:${CROP_TOP},\
scale=${STORE_W}:${STORE_H}:force_original_aspect_ratio=decrease:flags=lanczos,\
pad=${STORE_W}:${STORE_H}:(ow-iw)/2:(oh-ih)/2:color=${bg}" \
      "${STORE_DIR}/${name}.png"

    echo "${name}: README $(dimensions "${README_DIR}/${name}.png")  RuStore $(dimensions "${STORE_DIR}/${name}.png")"
    built=$((built + 1))
  done

  echo
  echo "собрано ${built}: ${README_DIR}/ и ${STORE_DIR}/"
  cmd_check
}

cmd_check() {
  need_ffmpeg
  [[ -d "${STORE_DIR}" ]] || die "нет ${STORE_DIR} — сначала build"

  shopt -s nullglob
  local files=("${STORE_DIR}"/*.png "${STORE_DIR}"/*.jpg)
  local count=${#files[@]}
  local bad=0

  echo
  echo "Проверка набора RuStore (${count} шт.):"
  ((count <= STORE_MAX_FILES)) \
    || { echo "  ✗ больше ${STORE_MAX_FILES} штук — магазин столько не примет"; bad=1; }

  for f in "${files[@]}"; do
    local size dim
    size="$(wc -c < "${f}" | tr -d ' ')"
    dim="$(dimensions "${f}")"
    if [[ "${dim}" != "${STORE_W}x${STORE_H}" ]]; then
      echo "  ✗ $(basename "${f}"): ${dim}, нужно ${STORE_W}x${STORE_H}"; bad=1
    elif ((size > STORE_MAX_BYTES)); then
      echo "  ✗ $(basename "${f}"): $((size / 1024)) КБ — больше 3 МБ"; bad=1
    else
      echo "  ✓ $(basename "${f}"): ${dim}, $((size / 1024)) КБ"
    fi
  done

  ((bad == 0)) || die "набор не готов к загрузке"
  echo "  всё в порядке"
}

case "${1:-}" in
  capture) shift; cmd_capture "$@" ;;
  build)   cmd_build ;;
  check)   cmd_check ;;
  *)
    sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
