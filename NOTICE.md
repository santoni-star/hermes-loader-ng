# NOTICE / Ліцензії третіх сторін

Hermes Loader (далі — «застосунок») не містить **агента** Hermes у складі
APK: Python-код агента завантажується з git-репозиторію вже на пристрої та
підпадає під ліцензію цього репозиторію. Разом із застосунком постачається
лише **веб-інтерфейс** (SPA) дашборда — незмінений бандл з того самого
репозиторію (розділ 2).

Нижче перелічено все, що дійсно постачається разом із застосунком.

---

## 1. PRoot

* Файли: `app/src/main/jniLibs/<abi>/{libproot.so, libprootloader.so,
  libprootloader32.so}` — під час встановлення Android розпаковує їх у
  `nativeLibraryDir`, застосунок запускає їх як зовнішні процеси.
* Джерело: <https://github.com/ahmed-alnassif/proot>, збірка
  `v26.10.08-a179d3e` (NDK r29), що базується на proot-me/proot.
* Ліцензія: **GNU GPL v2 або пізніша** (GPL-2.0-or-later).

PRoot розповсюджується як окремий виконуваний файл, який застосунок
запускає як зовнішній процес (а не компонує з власним кодом). Повний текст
ліцензії: <https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>.
Вихідний код PRoot доступний за посиланням вище; на запит його можна
отримати разом зі збіркою.

## 2. Веб-дашборд (SPA)

* Файли: `app/src/main/assets/web/**` — 59 файлів, 3 190 816 B
  (`index.html`, hashed JS/CSS-бандли Vite, `favicon.ico`, веб-шрифти,
  `UPSTREAM.txt`). Під час роботи розпаковуються у `files/webdist/` і
  монтуються в контейнер як `/opt/webdist`.
* Джерело: <https://github.com/NousResearch/hermes-agent>, тег **v0.22.5**;
  зібрано штатним `vite build`, узято готовий `hermes_cli/web_dist` без змін.
* Ліцензія: **MIT** (файл `LICENSE` репозиторію, © Nous Research).
* Усередині бандла — сторонні бібліотеки під MIT / ISC / Apache-2.0
  (зокрема React, xterm.js, Observable Plot, lucide-react); їхні
  повідомлення про ліцензії лишаються в мінімізованих файлах.
* Веб-шрифти: **JetBrains Mono** — SIL Open Font License 1.1; **Collapse**,
  **Mondwest**, **Rules** — з репозиторію Hermes Agent (окремих ліцензійних
  файлів upstream для них не постачає). Якщо плануєте публічне
  розповсюдження, уточніть умови для цих трьох шрифтів у upstream.
* Примітка про походження також лежить у `assets/web/UPSTREAM.txt`.

## 3. Debian rootfs

* Файли: не входять до APK — завантажуються під час першого запуску.
* Джерела (усі — офіційні збірки Debian, кожне звіряється за контрольною сумою):
  * `debian:bookworm-slim` — офіційний образ проєкту Debian на Docker Hub
    (`library/debian`, збирається debuerreotype з `deb.debian.org`);
  * <https://images.linuxcontainers.org/images/debian/bookworm/> — запасний
    варіант (збірки спільноти LXC з пакетів Debian).
* Tar-архіви з `cloud.debian.org` **не** використовуються: вони містять
  образ диска (`*.raw`), а не rootfs, тому застосунок їх відхиляє.
* Ліцензія: Debian складається з пакетів під вільними ліцензіями
  (Debian Social Contract / DFSG). Повний перелік — `/usr/share/doc/*/copyright`
  усередині самого rootfs.

## 4. Apache Commons Compress

* Використовується для розпакування `.tar.gz` (залежність `commons-io`).
* Версія: 1.26.2. Ліцензія: **Apache License 2.0**.
* <https://commons.apache.org/proper/commons-compress/>

## 5. XZ for Java

* Використовується для декодування `.tar.xz`.
* Версія: 1.9. Ліцензія: **public domain**
  (див. `org/tukaani/xz` у джерелах бібліотеки).
* <https://tukaani.org/xz/java.html>

## 6. Android framework

Застосунок побудований виключно на Android SDK (без AndroidX / Kotlin
runtime). Код SDK розповсюджується під Apache License 2.0.

## 7. Шрифт термінала

* Файл: `app/src/main/assets/fonts/CascadiaMono.ttf`.
* Підмножина **Cascadia Mono**, перейменована на «Hermes Mono» (вимога
  SIL OFL щодо Reserved Font Name для модифікованих версій).
* Призначення: однакова ширина клітинки для box-drawing (`─│┌╭╯`),
  блоків (`█▀`), braille-спінерів (`⠋⠙`) та стрілок, інакше рамки
  псевдографіки в TUI Hermes роз'їжджаються.
* Ліцензія: **SIL Open Font License 1.1**. Повний текст —
  `app/src/main/assets/fonts/OFL.txt`.
* Джерело: <https://github.com/microsoft/cascadia-code>.

## 8. Елементи інтерфейсу

Іконка застосунку (`res/drawable/ic_launcher.xml`) — власний vector
drawable, створений для цього проєкту; сторонніх зображень чи іконок не
використано. Решта інтерфейсу будується програмно на стандартних
віджетах Android.

---

## Збірка та модифікація

Якщо ви розповсюджуєте модифіковану версію застосунку разом із PRoot,
пам'ятайте про вимоги GPL-2.0+ щодо надання відповідного вихідного коду
(зокрема змін у самому PRoot, якщо такі є). Бандл дашборда поширюється під
MIT: зберігайте повідомлення про copyright і ліцензію (вони є в
`assets/web/UPSTREAM.txt` і в самих файлах бандла).
