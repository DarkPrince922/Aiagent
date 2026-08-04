# JSch подбирает реализации шифров, KEX и подписи по именам классов из конфигурации,
# поэтому R8 не видит ссылок на них и вырезал бы половину SSH-стека.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Jsoup обращается к парсерам рефлексивно и тянет опциональные классы javax.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn javax.annotation.**

# Worker'ы создаются WorkManager'ом по имени класса из базы задач.
-keep class app.jarvis.worker.** { *; }

# Стектрейсы падений агента должны оставаться читаемыми.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
