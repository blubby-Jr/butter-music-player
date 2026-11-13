package com.example.aimpmobile

import java.util.Locale

/**
 * Локалізація без XML-ресурсів: en (за замовчуванням), uk, ru.
 * Мова "auto" визначається за мовою пристрою; якщо вона не підтримується — англійська.
 */
object S {
    var lang = "en"
        private set

    fun resolve(pref: String, deviceLang: String = Locale.getDefault().language): String = when (if (pref == "auto") deviceLang else pref) {
        "uk" -> "uk"
        "ru" -> "ru"
        else -> "en"
    }

    fun setLang(pref: String) {
        lang = resolve(pref)
    }

    fun t(key: String): String {
        val a = T[key] ?: return key
        return a[when (lang) { "uk" -> 1; "ru" -> 2; else -> 0 }]
    }

    fun f(key: String, vararg args: Any): String = String.format(Locale.US, t(key), *args)

    /** Локалізована назва параметра (слайдера). */
    fun pl(d: ParamDef): String {
        if (d.id.startsWith("eq.band.")) {
            return if (lang == "en") d.label else d.label.replace("kHz", "кГц").replace("Hz", "Гц")
        }
        val key = "p." + d.id.removePrefix("effect.")
        return if (T.containsKey(key)) t(key) else d.label
    }

    val keys: Set<String> get() = T.keys

    private val T: Map<String, Array<String>> = mapOf(
        // загальне
        "ok" to arrayOf("OK", "Гаразд", "ОК"),
        "cancel" to arrayOf("Cancel", "Скасувати", "Отмена"),
        "back" to arrayOf("Back", "Назад", "Назад"),
        "apply" to arrayOf("Apply", "Застосувати", "Применить"),
        "prev" to arrayOf("Previous", "Попередній", "Предыдущий"),
        "next" to arrayOf("Next", "Наступний", "Следующий"),
        "play" to arrayOf("Play", "Грати", "Играть"),
        "pause" to arrayOf("Pause", "Пауза", "Пауза"),
        "volume" to arrayOf("Volume", "Гучність", "Громкость"),
        // головний екран
        "editor" to arrayOf("Editor", "Редактор", "Редактор"),
        "effects" to arrayOf("Effects", "Ефекти", "Эффекты"),
        "eq.short" to arrayOf("EQ", "EQ", "EQ"),
        "settings" to arrayOf("Settings", "Налаштування", "Настройки"),
        "import.btn" to arrayOf("Import", "Імпорт", "Импорт"),
        "viz.hint" to arrayOf("Tap the visualizer: cover art. Long tap: next mode", "Тап по візуалізації — обкладинка, довгий тап — інший режим", "Тап по визуализации — обложка, долгий тап — другой режим"),
        "search.hint" to arrayOf("Search tracks…", "Пошук треків…", "Поиск треков…"),
        "msg.nomusic" to arrayOf("No music found or no permission to access audio", "Музику не знайдено або немає дозволу на доступ до аудіо", "Музыка не найдена или нет разрешения на доступ к аудио"),
        // плейлисти
        "pl.all" to arrayOf("All music", "Уся музика", "Вся музыка"),
        "pl.new" to arrayOf("New playlist", "Новий плейлист", "Новый плейлист"),
        "pl.rename" to arrayOf("Rename playlist", "Перейменувати плейлист", "Переименовать плейлист"),
        "pl.delete" to arrayOf("Delete playlist", "Видалити плейлист", "Удалить плейлист"),
        "pl.delete.q" to arrayOf("Delete playlist «%s»?", "Видалити плейлист «%s»?", "Удалить плейлист «%s»?"),
        "pl.add" to arrayOf("Add to playlist…", "Додати до плейлиста…", "Добавить в плейлист…"),
        "pl.added" to arrayOf("Added to «%s»", "Додано до «%s»", "Добавлено в «%s»"),
        "pl.disable" to arrayOf("Disable in this playlist", "Вимкнути в цьому плейлисті", "Отключить в этом плейлисте"),
        "pl.enable" to arrayOf("Enable in this playlist", "Увімкнути в цьому плейлисті", "Включить в этом плейлисте"),
        "pl.enableall" to arrayOf("Enable all tracks", "Увімкнути всі треки", "Включить все треки"),
        "tags.edit" to arrayOf("Edit tags…", "Редактор тегів…", "Редактор тегов…"),
        "tags.title" to arrayOf("Title", "Назва", "Название"),
        "tags.artist" to arrayOf("Artist", "Виконавець", "Исполнитель"),
        "tags.album" to arrayOf("Album", "Альбом", "Альбом"),
        "tags.year" to arrayOf("Year", "Рік", "Год"),
        "tags.track" to arrayOf("Track №", "Трек №", "Трек №"),
        "tags.genre" to arrayOf("Genre", "Жанр", "Жанр"),
        "tags.save" to arrayOf("Save", "Зберегти", "Сохранить"),
        "tags.saved" to arrayOf("Tags saved", "Теги збережено", "Теги сохранены"),
        "tags.fail" to arrayOf("Could not save tags", "Не вдалося зберегти теги", "Не удалось сохранить теги"),
        "tags.unsupported" to arrayOf("Tag editing works for MP3 and FLAC files", "Редактор тегів працює з файлами MP3 та FLAC", "Редактор тегов работает с файлами MP3 и FLAC"),
        "tags.unsupported2" to arrayOf("This file's tag format is not supported (ID3v2.3/2.4 only)", "Формат тегів цього файлу не підтримується (лише ID3v2.3/2.4)", "Формат тегов этого файла не поддерживается (только ID3v2.3/2.4)"),
        "pl.addmusic" to arrayOf("Add music", "Додати музику", "Добавить музыку"),
        "pl.pick.files" to arrayOf("Files…", "Файли…", "Файлы…"),
        "pl.pick.folder" to arrayOf("Whole folder…", "Уся папка…", "Целая папка…"),
        "pl.addmusic.done" to arrayOf("Added tracks: %d", "Додано треків: %d", "Добавлено треков: %d"),
        "pl.addmusic.skipped" to arrayOf("Not found in the library: %d (not indexed yet?)", "Не знайдено в бібліотеці: %d (ще не проіндексовано?)", "Не найдено в библиотеке: %d (ещё не проиндексированы?)"),
        "pl.nopicker" to arrayOf("No file picker available", "Немає застосунку для вибору файлів", "Нет приложения для выбора файлов"),
        "pl.addmusic.none" to arrayOf("No matching tracks found", "Підходящих треків не знайдено", "Подходящих треков не найдено"),
        "pl.remove" to arrayOf("Remove from this playlist", "Прибрати з цього плейлиста", "Убрать из этого плейлиста"),
        // порядок
        "order.normal" to arrayOf("Normal order", "Звичайний порядок", "Обычный порядок"),
        "order.reverse" to arrayOf("Reverse order", "Зворотний порядок", "Обратный порядок"),
        "order.shuffle" to arrayOf("Shuffle", "Перемішати", "Перемешать"),
        // EQ
        "eq.title" to arrayOf("Equalizer · 18 bands", "Еквалайзер · 18 смуг", "Эквалайзер · 18 полос"),
        "eq.presets" to arrayOf("Presets", "Пресети", "Пресеты"),
        "eq.reset" to arrayOf("Reset EQ", "Скинути EQ", "Сбросить EQ"),
        "pre.flat" to arrayOf("Flat", "Рівний", "Ровный"),
        "pre.rock" to arrayOf("Rock", "Рок", "Рок"),
        "pre.pop" to arrayOf("Pop", "Поп", "Поп"),
        "pre.bass" to arrayOf("Bass boost", "Підсилення басу", "Усиление баса"),
        "pre.vocal" to arrayOf("Vocal", "Вокал", "Вокал"),
        "pre.treble" to arrayOf("Treble boost", "Підсилення високих", "Усиление высоких"),
        "pre.classical" to arrayOf("Classical", "Класика", "Классика"),
        "pre.electronic" to arrayOf("Electronic", "Електроніка", "Электроника"),
        // параметри
        "p.volume" to arrayOf("Volume", "Гучність", "Громкость"),
        "p.echo" to arrayOf("Echo", "Ехо", "Эхо"),
        "p.reverb" to arrayOf("Reverb", "Реверберація", "Реверберация"),
        "p.flanger" to arrayOf("Flanger", "Фланджер", "Флэнджер"),
        "p.chorus" to arrayOf("Chorus", "Хорус", "Хорус"),
        "p.bass" to arrayOf("Bass", "Бас", "Бас"),
        "p.stereo_width" to arrayOf("Stereo width", "Стереобаза", "Стереобаза"),
        "p.speed" to arrayOf("Speed", "Швидкість", "Скорость"),
        "p.tempo" to arrayOf("Tempo", "Темп", "Темп"),
        "p.pitch_st" to arrayOf("Pitch", "Пітч", "Питч"),
        // редактор
        "editor.pick" to arrayOf("Pick a track in the list", "Оберіть трек у списку", "Выберите трек в списке"),
        "editor.loop" to arrayOf("While the Editor is open the track loops", "Поки відкрито редактор, трек грає по колу", "Пока открыт редактор, трек играет по кругу"),
        "wave.loading" to arrayOf("Loading waveform…", "Хвиля завантажується…", "Волна загружается…"),
        "sel.mode.on" to arrayOf("Draw selection: ON", "Малювати виділення: УВІМК.", "Рисовать выделение: ВКЛ"),
        "sel.mode.off" to arrayOf("Draw selection: OFF", "Малювати виділення: ВИМК.", "Рисовать выделение: ВЫКЛ"),
        "sel.only.on" to arrayOf("Play selection only: ON", "Грати лише виділене: УВІМК.", "Играть только выделенное: ВКЛ"),
        "sel.only.off" to arrayOf("Play selection only: OFF", "Грати лише виділене: ВИМК.", "Играть только выделенное: ВЫКЛ"),
        "sel.hint" to arrayOf("Add several drops: turn drawing ON and drag on the waveform, or press A at the start and B at the end while listening. Drag green/red edges to adjust.", "Кілька дропів: увімкніть малювання й проведіть по хвилі, або натисніть A на початку та B в кінці під час прослуховування. Зелені/червоні межі можна перетягувати.", "Несколько дропов: включите рисование и проведите по волне, либо нажмите A в начале и B в конце во время прослушивания. Зелёные/красные границы можно перетаскивать."),
        "sel.a" to arrayOf("A set at %s", "Точку A поставлено: %s", "Точка A поставлена: %s"),
        "sel.needA" to arrayOf("Press A first", "Спочатку натисніть A", "Сначала нажмите A"),
        "sel.short" to arrayOf("Selection is too short", "Виділення надто коротке", "Выделение слишком короткое"),
        "sel.none" to arrayOf("No selections yet — draw or mark A/B", "Виділень ще немає — намалюйте або позначте A/B", "Выделений пока нет — нарисуйте или отметьте A/B"),
        "sel.delete" to arrayOf("Delete selected", "Видалити виділене", "Удалить выделенное"),
        "sel.clear" to arrayOf("Clear all", "Скинути все", "Сбросить всё"),
        "marker" to arrayOf("Marker", "Мітка", "Метка"),
        "marker.none" to arrayOf("No markers there", "Міток там немає", "Меток там нет"),
        "auto.title" to arrayOf("Automation (FL Studio style): effects, EQ, volume", "Автоматизація (стиль FL Studio): ефекти, еквалайзер, гучність", "Автоматизация (стиль FL Studio): эффекты, эквалайзер, громкость"),
        "auto.clear" to arrayOf("Clear lane", "Очистити доріжку", "Очистить дорожку"),
        "auto.empty" to arrayOf("«%s»: no nodes — tap to add", "«%s»: немає вузлів — торкніться, щоб додати", "«%s»: нет узлов — коснитесь, чтобы добавить"),
        "stamp.fx" to arrayOf("Add a stamp on Automation Track (effect just changed)", "Add a stamp on Automation Track (ефект, що щойно змінено)", "Add a stamp on Automation Track (только что изменённый эффект)"),
        "stamp.eq" to arrayOf("Add a stamp on Automatization (changed EQ bands)", "Add a stamp on Automatization (змінені смуги EQ)", "Add a stamp on Automatization (изменённые полосы EQ)"),
        "stamp.none" to arrayOf("Move a slider first", "Спочатку змініть слайдер", "Сначала измените слайдер"),
        "stamp.done" to arrayOf("Nodes added: %d", "Вузлів додано: %d", "Узлов добавлено: %d"),
        // налаштування
        "set.language" to arrayOf("Language", "Мова", "Язык"),
        "lang.auto" to arrayOf("Automatic (device language)", "Автоматично (мова пристрою)", "Автоматически (язык устройства)"),
        "set.theme" to arrayOf("Theme", "Тема", "Тема"),
        "theme.dark" to arrayOf("Dark", "Темна", "Тёмная"),
        "theme.light" to arrayOf("Light", "Світла", "Светлая"),
        "theme.amoled" to arrayOf("AMOLED black", "AMOLED чорна", "AMOLED чёрная"),
        "theme.custom" to arrayOf("Custom", "Власна", "Своя"),
        "col.bg" to arrayOf("Background", "Фон", "Фон"),
        "col.text" to arrayOf("Text", "Текст", "Текст"),
        "col.accent" to arrayOf("Accent", "Акцент", "Акцент"),
        "set.sound" to arrayOf("Sound & clipping", "Звук і кліпінг", "Звук и клиппинг"),
        "lim.off" to arrayOf("Limiter: off", "Лімітер: вимкнено", "Лимитер: выключен"),
        "lim.hard" to arrayOf("Limiter: hard (no samples above ceiling)", "Лімітер: жорсткий (нічого вище стелі)", "Лимитер: жёсткий (ничего выше потолка)"),
        "lim.soft" to arrayOf("Soft clipper (smooth saturation)", "М'який кліпер (плавне насичення)", "Мягкий клиппер (плавное насыщение)"),
        "set.ceiling" to arrayOf("Output ceiling", "Стеля рівня", "Потолок уровня"),
        "set.preamp" to arrayOf("Pre-amp (headroom)", "Пре-ампліфікація (запас)", "Преамп (запас)"),
        "set.headroom" to arrayOf("Auto headroom for EQ boost", "Авто-запас під підсилення EQ", "Авто-запас под усиление EQ"),
        "set.mono" to arrayOf("Mono downmix", "Моно-мікс", "Моно-микс"),
        "set.sound.note" to arrayOf("Hard limiter guarantees no clipping above the chosen ceiling (0 dB = digital maximum).", "Жорсткий лімітер гарантує відсутність кліпінгу вище обраної стелі (0 dB — цифровий максимум).", "Жёсткий лимитер гарантирует отсутствие клиппинга выше выбранного потолка (0 dB — цифровой максимум)."),
        "set.viz" to arrayOf("Visualization", "Візуалізація", "Визуализация"),
        "viz.bars" to arrayOf("Mode 1: spectrum bars", "Режим 1: стовпчики спектра", "Режим 1: столбцы спектра"),
        "viz.scope" to arrayOf("Mode 2: oscilloscope", "Режим 2: осцилограф", "Режим 2: осциллограф"),
        "viz.meter" to arrayOf("Mode 3: analog meters (VU)", "Режим 3: аналогові вимірювачі (VU)", "Режим 3: аналоговые измерители (VU)"),
        "viz.custom" to arrayOf("Mode 4: custom lines", "Режим 4: власні лінії", "Режим 4: свои линии"),
        "set.fps" to arrayOf("Visualizer refresh rate", "Частота оновлення візуалізації", "Частота обновления визуализации"),
        "set.fall" to arrayOf("Fall-off (smoothing)", "Опадання (згладжування)", "Спад (сглаживание)"),
        "set.viz.custom" to arrayOf("Mode 4 — custom lines", "Режим 4 — власні лінії", "Режим 4 — свои линии"),
        "set.bars" to arrayOf("Number of lines", "Кількість ліній", "Количество линий"),
        "set.thick" to arrayOf("Line thickness", "Товщина ліній", "Толщина линий"),
        "col.line1" to arrayOf("Line color", "Колір ліній", "Цвет линий"),
        "col.line2" to arrayOf("Second color (gradient)", "Другий колір (градієнт)", "Второй цвет (градиент)"),
        "set.gradient" to arrayOf("Gradient by height", "Градієнт за висотою", "Градиент по высоте"),
        "set.rainbow" to arrayOf("Rainbow", "Веселка", "Радуга"),
        "set.mirror" to arrayOf("Mirror (symmetric)", "Дзеркально (симетрично)", "Зеркально (симметрично)"),
        "set.peaks" to arrayOf("Peak caps", "Піки (шапочки)", "Пики (шапочки)"),
        "set.rounded" to arrayOf("Rounded line ends", "Закруглені кінці", "Закруглённые концы"),
        "set.playback" to arrayOf("Playback", "Відтворення", "Воспроизведение"),
        "sleep" to arrayOf("Sleep timer", "Таймер сну", "Таймер сна"),
        "sleep.off" to arrayOf("Off", "Вимкнено", "Выключен"),
        "sleep.min" to arrayOf("%d min", "%d хв", "%d мин"),
        "set.about" to arrayOf("About", "Про програму", "О программе"),
        // імпорт
        "import.title" to arrayOf("Import & search", "Імпорт і пошук", "Импорт и поиск"),
        "import.file" to arrayOf("Import a playlist or likes from a file", "Імпорт плейлиста чи лайків із файлу", "Импорт плейлиста или лайков из файла"),
        "import.help" to arrayOf("Export your list from a service (for example a Spotify CSV from Exportify, your data export from SoundCloud, a Google Takeout CSV for YouTube Music) or write «Artist - Title» lines in a text file. The app matches them with the music on this device and builds a playlist. Tracks that are not on the device are listed so you can search for them.", "Експортуйте список із сервісу (наприклад, CSV зі Spotify через Exportify, експорт даних SoundCloud, CSV з Google Takeout для YouTube Music) або напишіть рядки «Виконавець - Назва» в текстовому файлі. Програма зіставить їх із музикою на пристрої й створить плейлист. Треки, яких немає на пристрої, буде показано списком для пошуку.", "Экспортируйте список из сервиса (например, CSV из Spotify через Exportify, экспорт данных SoundCloud, CSV из Google Takeout для YouTube Music) или напишите строки «Исполнитель - Название» в текстовом файле. Приложение сопоставит их с музыкой на устройстве и создаст плейлист. Треки, которых нет на устройстве, будут показаны списком для поиска."),
        "import.pick" to arrayOf("Choose file (CSV / TXT / JSON)", "Обрати файл (CSV / TXT / JSON)", "Выбрать файл (CSV / TXT / JSON)"),
        "import.result" to arrayOf("Found %d of %d tracks on this device", "Знайдено %d з %d треків на пристрої", "Найдено %d из %d треков на устройстве"),
        "import.missing" to arrayOf("Not on this device (tap = search in Spotify, long tap = choose service):", "Немає на пристрої (тап — пошук у Spotify, довгий тап — вибір сервісу):", "Нет на устройстве (тап — поиск в Spotify, долгий тап — выбор сервиса):"),
        "import.search" to arrayOf("Search in streaming services", "Пошук у стрімінгових сервісах", "Поиск в стриминговых сервисах"),
        "import.query" to arrayOf("Artist or track name", "Виконавець або назва треку", "Исполнитель или название трека"),
        "import.note" to arrayOf("Search opens the service's app or website. Downloading or recording tracks from streaming services is not supported: it violates their terms and copyright. Use their official offline mode or buy the tracks, then they will appear here.", "Пошук відкриває застосунок або сайт сервісу. Завантаження чи запис треків зі стрімінгових сервісів не підтримується: це порушує їхні умови та авторські права. Скористайтеся їхнім офіційним офлайн-режимом або придбайте треки — тоді вони з'являться тут.", "Поиск открывает приложение или сайт сервиса. Скачивание или запись треков из стриминговых сервисов не поддерживается: это нарушает их условия и авторские права. Используйте их официальный офлайн-режим или купите треки — тогда они появятся здесь."),
        "msg.noapp" to arrayOf("No app or browser found to open the link", "Не знайдено застосунку чи браузера для відкриття посилання", "Не найдено приложения или браузера для открытия ссылки"),
        "msg.readfail" to arrayOf("Could not read the file", "Не вдалося прочитати файл", "Не удалось прочитать файл"),
        "msg.emptyimport" to arrayOf("No tracks found in the file", "У файлі не знайдено треків", "В файле не найдено треков")
    )
}
