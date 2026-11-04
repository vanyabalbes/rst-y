# Кредитна Життя (Fabric, Minecraft 1.21.11)

Збірка: Java 21 + Gradle.
1. Поклади в папку проєкту gradle wrapper (скопіюй `gradlew`, `gradlew.bat`, `gradle/` з https://github.com/FabricMC/fabric-example-mod) або виконай `gradle wrapper`.
2. `./gradlew build` -> jar у `build/libs/kredyt-mod-1.0.0.jar`.
3. Кидай jar + Fabric API у `mods/`.

Геймплей: телефон (ПКМ) -> меню mono; /bank credit|micro|repay|apartment|shop|buy.
Будівлі (ATB, Шаурмічна, Аврора, Нова Пошта) з'являються у нових чанках (~1/40).
Шахед (фантом) прилітає раз на ~10 хв; збити можна лише поруч із Фіді Міді (мікрофон або випадково разом із шахедом).
