# Ores & Drills

NeoForge-мод для Minecraft 1.21.1: многоуровневые буровые установки и настраиваемые крупные месторождения руд.

## Структура проекта

```text
src/
├── main/
│   ├── java/dev/
│   │   ├── FactoryExpansionMod.java  # точка входа и регистрация
│   │   ├── client/                   # клиентский рендер, экраны и создание мира
│   │   ├── command/                  # команды игрока
│   │   ├── compat/                   # необязательные интеграции (Jade)
│   │   ├── config/                   # конфигурация и пресеты мира
│   │   ├── drill/                    # реализации уровней буровых установок
│   │   ├── mixin/                    # минимальные Mixin-адаптеры Minecraft
│   │   ├── network/                  # payload'ы и регистрация сети
│   │   ├── registry/                 # все DeferredRegister-регистрации
│   │   └── world/                    # игровой домен: блоки, предметы, меню, мирогенерация
│   ├── resources/
│   │   ├── assets/ores_and_drills/ # клиентские ресурсы мода
│   │   ├── data/ores_and_drills/   # датапак-данные мода
│   │   ├── data/c/                   # common-теги
│   │   ├── data/minecraft/           # дополнения к vanilla-тегам
│   │   ├── ores_and_drills.mixins.json
│   │   └── pack.mcmeta
│   └── templates/META-INF/           # шаблон метаданных, обрабатываемый Gradle
└── test/java/                         # unit-тесты, повторяющие пакеты production-кода
```

`src/generated/resources` создаётся датагенератором и намеренно не хранится в Git. Папки `build`, `run`, `bin` и настройки IDE также являются локальными артефактами.

## Правила размещения нового кода

- Новый игровой контент размещайте в `world`: блоки — `world.block`, block entity — `world.block.entity`, предметы — `world.item`, меню — `world.inventory`.
- Код, относящийся только к конкретной буровой установке, держите рядом в `drill`; общую механику — в `world`.
- Не регистрируйте объекты внутри игровых классов: добавляйте их в подходящий класс `registry/`.
- Клиентский код не должен попадать в common-пакеты; для него используйте `client/`.
- JSON-модели, blockstate, языки и текстуры относятся к `assets/<mod_id>/`; рецепты, loot tables, теги и biome modifiers — к `data/<namespace>/`.
- На каждую изолированную математическую или конфигурационную часть добавляйте тест в зеркальный пакет `src/test/java`.

## Проверка

```powershell
.\gradlew.bat test
.\gradlew.bat build
```
