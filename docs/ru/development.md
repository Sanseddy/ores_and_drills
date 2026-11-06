# Разработка

[English version](../en/development.md)

## Сборка

Нужна Java 21.

```powershell
.\gradlew.bat build       # мод: build/libs/ores_and_drills-<версия>.jar
.\gradlew.bat test        # unit-тесты из src/test
.\gradlew.bat runClient   # клиент разработки в папке run
.\gradlew.bat runData     # датагенерация в src/generated/resources
```

`runData` работает в отдельной папке `build/recipe-datagen`. Сгенерированные JSON из
`src/generated/resources` хранятся в репозитории, служебный кэш генератора (`.cache`) — нет.

## GameTest

Интеграционные тесты лежат в `src/gameTest`. Они подключаются только флагом и в готовый jar не
попадают, как и их тестовые рецепты и правила из `src/gameTest/resources`.

```powershell
.\gradlew.bat build runGameTestServer -PcompactGameTests --console=plain
```

Тесты запускаются на сервере в отдельном мире `build/compact-gametest`. Часть отчётов пишется туда же
(`small-deposit-*.txt`) и в `build/reports/factorio-deposits/`.

Флаг `-PalexCavesGameTests` дополнительно подключает Alex's Caves и Citadel из `run/mods`. Без него тест
поздних пород `LateCaveRockGameTests` пропускается.

| Класс | Что проверяет |
|---|---|
| `SmallDepositAttemptGameTests` | поток попыток TINY/SMALL, доли, запасы, `/locate` |
| `SmallDepositFrequencyGameTests` | TINY/SMALL у правил с `frequency` и точный счёт forced |
| `VanillaOreVeinGameTests` | жила залежи совпадает с ванильным `Feature.ORE` и ничего не пишет в мир при сборе |
| `MediumLargeDigestGameTests` | отпечаток прогнозов MEDIUM/LARGE: ловит случайные изменения их генерации |
| `CompactDepositGameTests` | MEDIUM/LARGE по чанкам, точная сумма запаса, forced-продолжения, гравий |
| `CompactDepositTerrainGameTests` | пакетный расчёт породы совпадает с `getBaseColumn` |
| `CompactDepositConcurrencyGameTests` | нет взаимных блокировок при записи блоков |
| `OreDepositStorageGameTests` | сохранение и загрузка данных чанков и мира |
| `LateCaveRockGameTests` | породы, которые мод создаёт после рельефа (radrock Alex's Caves) |
| `LocateCellGameTests` | кандидаты `/locate` принадлежат своей ячейке |
| `OreSpawnDimensionsGameTests`, `OreTagsGameTests` | распознавание руд по фичам и тегам предметов |
| `OreDepositImpactGameTests` | залежи всех размеров теряют руду от ударов |
| `DrillMiningRecipeGameTests` | рецепты добычи, жидкости, порты, ручная добыча |
| `SeismicSurveyGameTests` | многоблочная станция, реестр геофонов, разведочный бур, детерминизм и точность съёмки, отсутствие загрузки чанков при радиусе 4096, бюджет тика, сохранение и возобновление |
| `SeismicForecastGameTests` | прогноз съёмки выбирает ровно тот материал, который предсказывает генерация |
| `OverworldCalibrationGameTests` | залежи на фоне реального поля пещер верхнего мира (ванильный шумовой маршрутизатор для сида) |

## Стиль кода

Формат — [google-java-format](https://github.com/google/google-java-format) в стиле AOSP: отступ
4 пробела, строка до 100 символов. Его поддерживает Spotless:

```powershell
.\gradlew.bat spotlessApply   # отформатировать
.\gradlew.bat spotlessCheck   # проверить
```

`build` формат не проверяет, чтобы неотформатированный файл не мешал запускать тесты. Форматтер
переносит длинные `//`-комментарии, но не склеивает их обратно в абзац: после него перечитайте
изменённые комментарии.

Комментарии пишутся на английском и объясняют, почему код устроен именно так: ограничения движка,
совместимость с модами, инварианты. Пересказывать код и описывать историю правок не нужно — для этого
есть git.

## Где что лежит

Генерация залежей — пакет `dev.world.level.levelgen`:

| Классы | Задача |
|---|---|
| `DepositLayouts`, `TierDepositResolver` и его реализации | сетки и seed-пространства размеров |
| `SmallDepositAttemptPlanner`, `SmallDepositAttemptMath`, `SmallDepositDiagnostics` | попытки TINY/SMALL и их счётчики в `debug.log` |
| `VanillaOreVein` | сбор жилы через ванильный `Feature.ORE` |
| `CaveWallDetector`, `CaveWallAnchor`, `WallDepositGeometry` | стены пещер и форма у стены |
| `CompactDepositGeometry`, `CompactDepositTerrain` | форма MEDIUM/LARGE и пакетный расчёт породы |
| `OreDepositFeature` | размещение залежей всех размеров |
| `DataDrivenOreDepositBiomeModifier`, `DataDrivenDepositLedger` | правила датапаков и учёт forced |
| `DepositLocator`, `OreDepositLocateCommand` | `/locate oredeposit` |
| `ConfirmedDepositIndex`, `OreDepositChunkData` | сохранение |
| `DepositWorldgenGuard` | защита MEDIUM/LARGE от гравия |
| `SeismicDepositForecast` | прогноз MEDIUM/LARGE для сейсморазведки, только чтение |

Сейсморазведка находится в `dev.world.geology` ([сейсморазведка](seismic-survey.md)):

| Классы | Назначение |
|---|---|
| `RockProperties`, `RockCatalog`, `RockPhysics` | плотность, скорость, Q; датапаки `rock_physics` |
| `SeismicMath`, `ArrayGeometry` | формулы отражения; геометрия приёмников и точность |
| `DepositBodies`, `NaturalGeology`, `CavityGrid` | тела модели: залежи с ореолом, естественные структуры, пещеры |
| `SeismicForwardModel`, `SurveyAnomalies`, `SurveyComposite` | карты с привязкой событий к источникам, выделение аномалий, сведение |
| `ore.OreSignatureTable`, `ore.OreSignatureMath`, `ore.OreIdentification` | таблица руд из данных игры, условные минералы, байесовское распознавание для станции и бура |
| `SurveyInterpretation`, `SurveyTarget`, `ScannerUpgrade`, `SurveyPreset` | цели с ошибками и вариантами; уровни улучшения; программы съёмки |
| `survey.SeismicSurveyService`, `survey.SurveyJob` | съёмки по снимкам мира с бюджетом на тик |
| `GeologySavedData`, `SurveyArchive`, `SurveyRecord` | геофоны, скважины, архивы станций |
| `station.*`, `exploration.*`, `GeophoneBlock`, `UpgradeModuleItem` | блоки, блок-сущности, меню, модули улучшения (сканер, анализатор пещер) |
| `CaveAnalyzer`, `CaveAnalysis`, `survey.CaveSnapshot`, `survey.CaveLinkCheck` | уровни анализатора пещер; от решётки зондов к пещерам, стенкам и соединениям (чистый код); чтение решётки и проверка соединений в серверном потоке |
| `lidar.LidarMap`, `lidar.LidarMapStorage`, `lidar.StationLidarData` | 3D-карта: ячейки с ревизиями, правила усреднения и замены; хранение `Format=1`; файл станции |
| `lidar.LidarExtraction`, `lidar.LidarSweep`, `lidar.LidarObjectMerge`, `lidar.CandidateFusion`, `lidar.OrePriors` | результаты съёмки в отсчёты и объекты; раскрытие по фронту; уточнение целей и объединение распознавания |
| `lidar.LidarSync`, `lidar.LidarCodec`, `lidar.LidarWire` | инкрементальная синхронизация со зрителями в пределах бюджета на тик; сетевой формат |

Экраны станции и бура (`dev.client.geology`) берут все области из чистых `layout.StationLayout` и
`ColumnLayout`, а весь текст подгоняют `layout.TextFit` по реальному шрифту; `StationLayoutTest`,
`ColumnLayoutTest` и `TextFitTest` проверяют их на масштабированных размерах от 320×180 и с очень
длинными надписями. 3D-вид — `lidar.LidarView`: чистые `LidarCamera` (перспективная орбитальная камера)
и `LidarPoints` (детерминированное разворачивание отсчётов в точки) под `LidarRenderer`, который держит
GPU-буферы по ячейкам и рисует их ядровым шейдером `ores_and_drills:lidar_point`; `LidarClientCache`
хранит карты сеанса. Метки ставятся через `dev.client.map.MapWaypoints` и по одному адаптеру на мод карты
в `dev.compat` (`XaeroWaypoints`, `JourneyMapWaypoints`, `FtbChunksWaypoints`).

Правила съёмки: состояние мира читается только в серверном потоке, и ни один чанк при этом не
загружается и не генерируется; прогноз, зондирование пещер и модель работают в фоновом исполнителе над
неизменяемыми снимками; всё случайное — функция сида и конфигурации съёмки; клиент получает только
обработанные измерения и интерпретированные цели, а не идентификаторы залежей, истинные положения и
тела модели; у руд нет констант для конкретных руд — их свойства берутся только из тегов, уровня
инструмента, твёрдости и записанной генерации.

## Правила генерации

Каждое из этих правил появилось после настоящего зависания или потери данных. Нарушение обычно
проявляется не в тестах, а в игре.

- **Поток генерации пишет только в свой чанк.** У соседнего чанка может ещё не быть рельефа, и
  повышать его статус ради проверки нельзя. Части залежи в соседях ставятся, когда генерируются они
  сами.
- **Монитор `ConfirmedDepositIndex` не держится во время записи в мир.** `Level.setBlock` вызывает
  обработчики других модов (например, физику Sable), а те могут ждать генерации соседнего чанка. Поток
  генерации в это время ждёт тот же монитор — взаимная блокировка. Монитор защищает только коллекции
  индекса; запись блоков, обработчики и синхронизация идут после его освобождения. Повторный вход в
  размещение занятого чанка пропускается.
- **Защита от гравия только читает готовые данные.** `DepositWorldgenGuard` и `OreFeatureMixin` не
  строят планы, не ищут кандидатов и не считают шум. Построение плана из этого места однажды заняло
  около 128 секунд процессорного времени на первом чанке.
- **Порода считается пакетно.** `CompactDepositTerrain` использует один `NoiseChunk` на чанк и
  ограничивает расчёт высотой залежи. `getBaseColumn` на каждую колонку LARGE в 125 раз медленнее и
  выделяет гигабайты памяти. Свой `getBaseColumn` вызывается только у сторонних генераторов и
  нестандартных размеров ячеек.
- **Генерация детерминирована.** Автоматические планы зависят только от seed и координат, а не от
  порядка генерации, порядка загрузки датапаков или идентичности объектов JVM. Исключение — выбор
  центров forced-залежей.
- **Кэши ограничены:** на мир 24 геометрии, 96 планов, 2048 кандидатов и 64 маски защиты от гравия.
  Кэши, привязанные к миру, держат его по слабой ссылке.

## Формат сохранения

`OreDepositChunkData`, `ConfirmedDepositIndex` и незавершённые forced-планы пишут `Format=1` и не читают
другие версии. При несовместимом изменении формата поднимите номер и проверьте загрузку мира тестами
`OreDepositStorageGameTests`.

Все SavedData мода наследуют `FastSavedData`. NBT собирается заново при каждом сохранении и потом не
меняется, поэтому глубокое копирование NeoForge пропускается, а сжатие (`BEST_SPEED`) идёт в потоке
ввода-вывода.

## Расширение

- Условие рецепта бура: `DrillMiningCondition.register(id, mapCodec)` при инициализации мода на
  сервере и клиенте, до загрузки рецептов ([рецепты добычи](drill-mining-recipes.md)).
- Новый бур со своим уровнем рецептов: канонический конструктор `MiningDrillTier`.
- Правила месторождений и пресеты — только JSON ([датапаки](ore-deposit-datapacks.md)).
