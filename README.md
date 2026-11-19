# NothingBlurIcons 🪟✨

[![Nothing OS](https://img.shields.io/badge/Nothing%20OS-5.0-black?style=for-the-badge&logo=android)](https://nothing.tech)
[![LSPosed](https://img.shields.io/badge/LSPosed-Supported-5C6BC0?style=for-the-badge)](https://github.com/LSPosed/LSPosed)
[![Refresh Rate](https://img.shields.io/badge/120%20FPS-Ultra%20Smooth-success?style=for-the-badge)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=for-the-badge)](LICENSE)

An **Xposed / LSPosed** module for Nothing OS that brings authentic, dynamic **frosted glass wallpaper blur** to home screen and folder app icons — seamlessly matching Nothing OS 5.0 widgets, media player, and weather cards.

[English](#english) | [Русский](#русский)

---

# English

## 💡 Acknowledgements & Inception

> [!IMPORTANT]
> **Special thanks to [@linuxct](https://github.com/linuxct)** — the creator of [**nothing-icongen**](https://github.com/linuxct/nothing-icongen).
>
> This project was inspired by and built to work in synergy with **`nothing-icongen`**. 
> While `nothing-icongen` generates the translucent icon bases for Nothing OS, **NothingBlurIcons** hooks directly into Nothing Launcher's native `FrostedBackdropProvider` to render real-time, hardware-accelerated frosted glass wallpaper blur behind each icon.
> 
> Without `linuxct`'s brilliant work, this project would not exist!

## ✨ Features

- 🪟 **Authentic Frosted Glass Blur:** Hooks directly into Nothing OS 5.0's proprietary `FrostedBackdropProvider` and `FrostedBackdropDrawable` — 100% identical in blur radius, lighting, and tint to official Nothing OS 5.0 widgets (Clock, Weather, Media Player).
- 📁 **Smart Folder Separation:** Icons inside opened folders dynamically receive a soft milky-glass highlight (`0x48FFFFFF` in light mode / `0x2AFFFFFF` in dark mode) so that circular icon plates cleanly stand out from the folder's glass backdrop without blending in.
- 🚀 **Buttery Smooth 120 FPS:** Zero lag or stutter. Highly optimized with frame-rate throttled invalidations (8ms budget), reflection lookup caching, and weak view-type caching. Measured jank rate under 2.0% on Snapdragon SM8735 / Nothing Phone (3).
- 🔍 **Classic Monochrome in All Apps & Search:** Automatically resolves and applies authentic Nothing OS monochrome themed icons in the app drawer and universal search bar with native charcoal/off-white plates.
- 🔄 **Real-Time Wallpaper & Theme Sync:** Reacts instantaneously when switching between Dark/Light themes, paging home screen workspaces, or changing wallpapers.

## 📸 Screenshots

| Home Screen Blur | Folder Glass Separation |
|:---:|:---:|
| <img src="docs/screenshots/home_overview.png" width="340"/> | <img src="docs/screenshots/folder_open.png" width="340"/> |

| Folder Icons Close-up | Drawer Search Integration |
|:---:|:---:|
| <img src="docs/screenshots/folder_crop.png" width="340"/> | <img src="docs/screenshots/drawer_search.png" width="340"/> |

## 📱 Compatibility

Requires any Nothing smartphone running **Nothing OS 5.0** (Android 15+):

- **Nothing Phone (3)** — Fully tested & verified ✅
- **Nothing Phone (2a) & Phone (2a) Plus** — Fully supported on Nothing OS 5.0 ✅
- **Nothing Phone (2)** — Supported on Nothing OS 5.0 ports ✅

## 🛠️ Prerequisites & Installation

### 1. Requirements
1. **Root access** via [KernelSU](https://github.com/tiann/KernelSU), [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next), [Magisk](https://github.com/topjohnwu/Magisk), or [APatch](https://github.com/bmax121/APatch).
2. **LSPosed** (or Zygisk Next / Vector) installed and active.
3. [**nothing-icongen**](https://github.com/linuxct/nothing-icongen) icon pack installed (set in *Settings -> Customization -> Icon Pack*).

### 2. Install Module
1. Download the latest `NothingBlurIcons.apk` from [**Releases**](../../releases).
2. Install the APK on your device.
3. Open your **LSPosed Manager**, go to **Modules**, and enable **NothingBlurIcons**.
4. Ensure the scope has **Nothing Launcher** (`com.nothing.launcher`) checked.
5. Force close or restart Nothing Launcher (`adb shell killall -9 com.nothing.launcher` or reboot the device).
6. Enjoy dynamic frosted glass icons!

## 🏗️ Building from Source

```bash
git clone https://github.com/Ak1o-T/NothingBlurIcons.git
cd NothingBlurIcons
./build.sh
```
The output signed APK will be generated at `bin/NothingBlurIcons.apk`.

## 📄 License

This project is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---

# Русский

## 💡 Вдохновение и благодарности

> [!IMPORTANT]
> **Особая благодарность [@linuxct](https://github.com/linuxct)** — автору генератора иконок [**nothing-icongen**](https://github.com/linuxct/nothing-icongen).
>
> Этот проект был вдохновлён и создан для совместной работы в тандеме с **`nothing-icongen`**.
> В то время как `nothing-icongen` формирует полупрозрачные основы для иконок Nothing OS, модуль **NothingBlurIcons** подключается напрямую к нативному системному движку `FrostedBackdropProvider` в Nothing Launcher и отрисовывает настоящее аппаратное размытие обоев под каждой иконкой.
> 
> Без задумки и работы `linuxct` создание этого модуля было бы невозможным!

## ✨ Возможности и особенности

- 🪟 **Настоящее матовое стекло:** Подключается напрямую к фирменному движку Nothing OS 5.0 (`FrostedBackdropProvider` и `FrostedBackdropDrawable`) — радиус размытия, глубина и оттенок на 100% совпадают с системными виджетами Nothing OS 5.0 (Часы, Погода, Плеер).
- 📁 **Умное отделение иконок в папках:** Внутри открытых папок круги иконок автоматически получают деликатную подсветку светлым матовым стеклом (`0x48FFFFFF` в светлой теме / `0x2AFFFFFF` в тёмной), благодаря чему они отчётливо видны и не сливаются с задним фоном самой папки.
- 🚀 **Идеальные 120 FPS:** Никаких просадок и фризов. Полный аппаратный троттлинг перерисовок под 8 мс (120 Гц), кэширование рефлексии и отсутствие аллокаций памяти в цикле отрисовки. Уровень пропущенных кадров (jank rate) менее 2.0% на Snapdragon SM8735 / Nothing Phone (3).
- 🔍 **Классический монохром в меню приложений и поиске:** Автоматически подставляет оригинальные монохромные иконки Nothing на фирменных плашках в общем списке приложений и в поисковой строке.
- 🔄 **Мгновенная синхронизация темы и обоев:** Реакция в реальном времени при смене светлой/тёмной темы, перелистывании рабочих столов и обновлении обоев.

## 📸 Скриншоты работы

| Размытие на рабочем столе | Светлое стекло в папках |
|:---:|:---:|
| <img src="docs/screenshots/home_overview.png" width="340"/> | <img src="docs/screenshots/folder_open.png" width="340"/> |

| Крупный план иконок папки | Поиск в меню приложений |
|:---:|:---:|
| <img src="docs/screenshots/folder_crop.png" width="340"/> | <img src="docs/screenshots/drawer_search.png" width="340"/> |

## 📱 Совместимость с устройствами

Требуется смартфон Nothing с оболочкой **Nothing OS 5.0** (Android 15+):

- **Nothing Phone (3)** — Протестировано и подтверждено ✅
- **Nothing Phone (2a) и Phone (2a) Plus** — Полная поддержка на Nothing OS 5.0 ✅
- **Nothing Phone (2)** — Поддерживается на портах Nothing OS 5.0 ✅

## 🛠️ Требования и установка

### 1. Что потребуется
1. **Root-доступ** через [KernelSU](https://github.com/tiann/KernelSU), [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next), [Magisk](https://github.com/topjohnwu/Magisk) или [APatch](https://github.com/bmax121/APatch).
2. Установленный и активный фреймворк **LSPosed** (или Zygisk Next / Vector).
3. Установленный и применённый икон-пак [**nothing-icongen**](https://github.com/linuxct/nothing-icongen) (выбирается в *Настройки -> Персонализация -> Набор значков*).

### 2. Установка модуля
1. Скачай актуальный файл `NothingBlurIcons.apk` со страницы [**Релизов**](../../releases).
2. Установи APK на смартфон.
3. Открой **LSPosed Manager**, перейди в раздел **Модули** и включи **NothingBlurIcons**.
4. Убедись, что в списке приложений для модуля отмечена галочка напротив **Nothing Launcher** (`com.nothing.launcher`).
5. Перезагрузи Nothing Launcher (`adb shell killall -9 com.nothing.launcher` или перезагрузи телефон).
6. Наслаждайся динамическим матовым размытием иконок!

## 🏗️ Сборка из исходников

```bash
git clone https://github.com/Ak1o-T/NothingBlurIcons.git
cd NothingBlurIcons
./build.sh
```
Готовый подписанный APK будет сохранён в `bin/NothingBlurIcons.apk`.

## 📄 Лицензия

Проект распространяется под открытой лицензией Apache License 2.0. Подробности в файле [LICENSE](LICENSE).
