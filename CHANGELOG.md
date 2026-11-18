# Changelog

## 8.4.2 (from 8.4.1)

### New
- **SoundCloud** and **Yandex Music** sources, with a sliding "bubble" selector in search to switch between sources.
- **Yandex Music**: search "likes" / "мне нравится" lists your liked songs; audio quality choice; a clear warning when the token has expired or was refused; falls back to the newer file-info call when no plain file is offered.
- **Proxy**: now covers the whole app (SoundCloud, Yandex, lyrics, covers, updates, streams) with a "Whole app" switch; your home network (TV, car, other phones) always stays direct. The "Check connection" button works at any time and shows a small pill with the ping.
- **Russian**: translation completed across the app.
- **Settings**: iOS-style grouped list with coloured tiles (including App behaviour); profile cards removed; "Check update" row at the bottom.
- **Font**: Claude-style serif option.
- **App icon**: glass icon in Customize → Icons.
- **Widgets**: four new home-screen widgets, controls pill and player card, each in glass and default looks.
- Own icons for the SoundCloud and Yandex module cards.

### Fixed / improved
- Empty black space under scrolling pages and settings trimmed.
- Widget circles no longer clipped on long widgets.
- Fetch DASH segments as-is instead of resolving them on YouTube.
- Landscape follows the phone again and fills the camera strip; floating sheets sit in the middle; the wide bar shrinks on short screens; pill and card fixes.
- Turkish translation added.

### Build
- Signing key can come from a repository secret (`ANDROID_KEYSTORE_BASE64`) so updates install over each other; new release workflow.

---

# Список изменений

## 8.4.2 (с 8.4.1)

### Новое
- Источники **SoundCloud** и **Яндекс Музыка**, в поиске выбор источника с плавающей «пузырьковой» плашкой.
- **Яндекс Музыка**: запрос «мне нравится» или «likes» показывает любимые треки; выбор качества; понятное предупреждение, когда токен истёк или отклонён; запасной способ получения файла.
- **Прокси**: работает для всего приложения (SoundCloud, Яндекс, тексты, обложки, обновления, потоки) с переключателем «Всё приложение»; домашняя сеть (ТВ, авто, другие телефоны) всегда идёт напрямую. Кнопка «Проверить соединение» работает всегда и показывает сверху плашку с пингом.
- **Русский язык**: перевод доведён до конца по всему приложению.
- **Настройки**: сгруппированный список в стиле iOS с цветными плитками (в том числе App behaviour); убраны карточки профиля; строка «Check update» внизу списка.
- **Шрифт**: вариант с засечками в стиле Claude.
- **Иконка приложения**: стеклянная иконка в Customize → Иконки.
- **Виджеты**: четыре новых виджета (полоса управления и карточка плеера), каждый в стеклянном и обычном виде.
- Собственные иконки модулей SoundCloud и Яндекс Музыки.

### Исправлено и улучшено
- Убраны пустые чёрные области под прокручиваемыми страницами и настройками.
- Круги в длинных виджетах больше не обрезаются.
- DASH-сегменты загружаются как есть, без разрешения через YouTube.
- Альбомная ориентация снова следует за телефоном и заполняет область камеры; плавающие окна стоят по центру; широкая панель сжимается на низких экранах; правки пилюли и карточек.
- Добавлен турецкий перевод.

### Сборка
- Ключ подписи берётся из секрета репозитория (`ANDROID_KEYSTORE_BASE64`), поэтому обновления ставятся поверх друг друга; добавлен workflow релиза.
